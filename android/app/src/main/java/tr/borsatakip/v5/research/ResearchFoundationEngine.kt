package tr.borsatakip.v5.research

class ResearchFoundationEngine {
    private data class Draft(val document: ResearchInputDocument, val evidence: Evidence, val eventId: String)

    fun build(symbol: String, documents: List<ResearchInputDocument>, asOfTime: Long): ResearchSnapshot {
        require(symbol.isNotBlank()) { "symbol boş olamaz" }
        require(asOfTime > 0L) { "asOfTime pozitif olmalıdır" }
        val normalizedSymbol = symbol.trim().uppercase()
        val createdAt = System.currentTimeMillis()
        val sanitized = documents
            .asSequence()
            .filter { it.title.isNotBlank() && it.sourceName.isNotBlank() }
            .filter { it.symbol.isNullOrBlank() || it.symbol.equals(normalizedSymbol, ignoreCase = true) }
            .distinctBy { it.documentId }
            .toList()

        // Temporal eligibility is resolved before syndication/independence so a
        // future record can never demote a valid point-in-time record to duplicate.
        val rejectedIds = sanitized.filterNot { PointInTimePolicy.isEligible(it, asOfTime) }.map { it.documentId }.toSet()
        val eligible = sanitized.filter { it.documentId !in rejectedIds }
        val grouping = SyndicationDetector.group(eligible)
        val resolution = ClaimEventResolver.resolve(eligible, normalizedSymbol)

        val drafts = eligible.map { document ->
            val sourceType = SourceIdentityPolicy.sourceType(document)
            val canonical = SourceIdentityPolicy.canonicalSourceId(document.sourceName, document.url)
            val claimId = resolution.claimIdByDocument.getValue(document.documentId)
            val eventId = resolution.eventIdByDocument.getValue(document.documentId)
            val evidenceId = ResearchHash.id("evidence", document.documentId, canonical, document.publishedAt?.toString().orEmpty())
            Draft(
                document = document,
                eventId = eventId,
                evidence = Evidence(
                    evidenceId = evidenceId,
                    sourceId = ResearchHash.id("source", document.sourceName, document.url.orEmpty()),
                    canonicalSourceId = canonical,
                    originSourceId = document.originSourceId,
                    sourceType = sourceType,
                    sourceName = document.sourceName,
                    title = document.title,
                    url = document.url,
                    eventTime = document.eventTime,
                    publishedAt = document.publishedAt,
                    receivedAt = document.receivedAt,
                    parsedAt = createdAt,
                    availableAt = document.availableAt,
                    quality = SourceIdentityPolicy.quality(document, sourceType, asOfTime),
                    independenceGroupId = grouping.groupByDocumentId[document.documentId] ?: ResearchHash.id("ind", canonical),
                    claimIds = listOf(claimId),
                    supportsClaim = document.supportsClaim,
                    providerVerified = document.providerVerified,
                    sourceIdentityVerified = SourceIdentityPolicy.authoritativeIdentityVerified(document, sourceType),
                    temporalEligible = true
                )
            )
        }

        val claimIds = drafts.flatMap { it.evidence.claimIds }.distinct()
        val claims = claimIds.map { claimId ->
            val rows = drafts.filter { claimId in it.evidence.claimIds }
            val evidenceRows = rows.map { it.evidence }
            val supporting = evidenceRows.filter { it.supportsClaim }
            val contradicting = evidenceRows.filterNot { it.supportsClaim }
            val independentSupport = supporting.map { it.independenceGroupId }.distinct().size
            val independentContradiction = contradicting.map { it.independenceGroupId }.distinct().size
            val authoritativeSupport = supporting.any { it.sourceType in setOf(SourceType.KAP, SourceType.REGULATOR, SourceType.OFFICIAL_FINANCIAL) && it.sourceIdentityVerified }
            val status = when {
                evidenceRows.isEmpty() -> VerificationStatus.INSUFFICIENT
                independentContradiction > independentSupport && independentContradiction > 0 -> VerificationStatus.CONTRADICTED
                authoritativeSupport -> VerificationStatus.VERIFIED
                independentSupport >= 2 -> VerificationStatus.VERIFIED
                independentSupport == 1 -> VerificationStatus.PARTIALLY_VERIFIED
                else -> VerificationStatus.UNVERIFIED
            }
            val eventIds = rows.map { it.eventId }.distinct().sorted()
            ResearchClaim(
                claimId = claimId,
                eventId = eventIds.firstOrNull(),
                text = evidenceRows.firstOrNull()?.title.orEmpty(),
                status = status,
                supportingEvidenceIds = supporting.map { it.evidenceId },
                contradictingEvidenceIds = contradicting.map { it.evidenceId },
                confidence = claimConfidence(status, evidenceRows)
            )
        }

        val events = drafts.groupBy { it.eventId }.map { (eventId, rows) ->
            val evidenceRows = rows.map { it.evidence }
            val claimIdsForEvent = evidenceRows.flatMap { it.claimIds }.distinct().sorted()
            val types = rows.map { EventClassifier.classify(it.document.title, it.document.summary) }
            ResearchEvent(
                eventId = eventId,
                eventType = types.firstOrNull { it != EventType.OTHER } ?: types.firstOrNull() ?: EventType.OTHER,
                symbol = normalizedSymbol,
                eventTime = evidenceRows.mapNotNull { it.eventTime }.minOrNull(),
                publishedAt = evidenceRows.mapNotNull { it.publishedAt }.minOrNull(),
                firstSeenAt = evidenceRows.minOfOrNull { it.receivedAt } ?: createdAt,
                relatedClaimIds = claimIdsForEvent,
                relatedEvidenceIds = evidenceRows.map { it.evidenceId }
            )
        }.sortedBy { it.eventId }

        val graph = EvidenceGraph(
            events = events,
            claims = claims,
            evidence = drafts.map { it.evidence },
            rejectedEvidenceIds = rejectedIds.toList().sorted(),
            duplicateEvidenceIds = grouping.duplicateDocumentIds.toList().sorted()
        )
        val confidence = confidence(graph)
        val status = when {
            graph.evidence.isEmpty() -> ResearchStatus.INSUFFICIENT
            graph.claims.none { it.status == VerificationStatus.VERIFIED } -> ResearchStatus.PARTIAL
            graph.rejectedEvidenceIds.isNotEmpty() -> ResearchStatus.PARTIAL
            else -> ResearchStatus.COMPLETED
        }
        val sourceHash = ResearchHash.id(
            "snapshot",
            *graph.evidence.sortedBy { it.evidenceId }.map {
                listOf(it.evidenceId, it.canonicalSourceId, it.originSourceId, it.independenceGroupId, it.publishedAt, it.availableAt).joinToString("|")
            }.toTypedArray()
        )
        return ResearchSnapshot(
            researchId = ResearchHash.id("research", normalizedSymbol, asOfTime.toString(), sourceHash),
            symbol = normalizedSymbol,
            asOfTime = asOfTime,
            createdAt = createdAt,
            status = status,
            graph = graph,
            confidence = confidence,
            dataVersion = ResearchDataContract.DATA_VERSION,
            rulesVersion = ResearchDataContract.RULES_VERSION,
            modelVersion = ResearchDataContract.MODEL_VERSION,
            sourceSnapshotHash = sourceHash
        )
    }

    private fun claimConfidence(status: VerificationStatus, rows: List<Evidence>): Double {
        if (rows.isEmpty()) return 0.0
        val base = ResearchMath.average(rows.map { (it.quality.authorityScore + it.quality.parsingConfidence) / 2.0 })
        val independent = rows.map { it.independenceGroupId }.distinct().size
        val multiplier = when (status) {
            VerificationStatus.VERIFIED -> if (independent >= 2) 1.0 else 0.92
            VerificationStatus.PARTIALLY_VERIFIED -> 0.72
            VerificationStatus.UNVERIFIED -> 0.45
            VerificationStatus.CONTRADICTED -> 0.25
            VerificationStatus.INSUFFICIENT -> 0.0
        }
        return ResearchMath.bounded(base * multiplier)
    }

    private fun confidence(graph: EvidenceGraph): ConfidenceBreakdown {
        if (graph.evidence.isEmpty()) return ConfidenceBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val e = graph.evidence
        val claims = graph.claims
        val verified = claims.count { it.status == VerificationStatus.VERIFIED }
        val consistency = if (claims.isEmpty()) 0.0 else claims.map { claim ->
            val support = claim.supportingEvidenceIds.size.toDouble()
            val contra = claim.contradictingEvidenceIds.size.toDouble()
            if (support + contra == 0.0) 0.0 else 100.0 * support / (support + contra)
        }.average()
        val completeness = if (claims.isEmpty()) 0.0 else 100.0 * verified / claims.size
        return ConfidenceBreakdown(
            sourceConfidence = ResearchMath.average(e.map { it.quality.authorityScore }),
            claimConfidence = ResearchMath.average(claims.map { it.confidence }),
            freshness = ResearchMath.average(e.map { it.quality.freshnessScore }),
            dataCompleteness = completeness,
            crossSourceConsistency = consistency,
            parsingConfidence = ResearchMath.average(e.map { it.quality.parsingConfidence })
        )
    }
}
