package no.nav.amt.deltaker.bff.deltaker.kafka

import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.amt.deltaker.bff.Environment
import no.nav.amt.deltaker.bff.application.plugins.objectMapper
import no.nav.amt.deltaker.bff.deltaker.DeltakerService
import no.nav.amt.deltaker.bff.deltaker.navbruker.NavBrukerService
import no.nav.amt.deltaker.bff.deltaker.vurdering.VurderingService
import no.nav.amt.deltaker.bff.deltakerliste.DeltakerlisteRepository
import no.nav.amt.deltaker.bff.unleash.UnleashToggle
import no.nav.amt.deltaker.bff.utils.buildManagedKafkaConsumer
import no.nav.amt.lib.kafka.Consumer
import org.slf4j.LoggerFactory
import java.util.UUID

class DeltakerV2Consumer(
    private val deltakerService: DeltakerService,
    private val deltakerlisteRepository: DeltakerlisteRepository,
    private val vurderingService: VurderingService,
    private val navBrukerService: NavBrukerService,
    private val unleashToggle: UnleashToggle,
) : Consumer<UUID, String?> {
    private val log = LoggerFactory.getLogger(javaClass)

    private val consumer = buildManagedKafkaConsumer(
        topic = Environment.AMT_DELTAKERV2_TOPIC,
        consumeFunc = ::consume,
    )

    override suspend fun consume(key: UUID, value: String?) {
        if (value == null) {
            log.info("Mottok tombstone for deltaker $key - sletter deltaker")
            deltakerService.delete(key)
            return
        }

        val deltakerV2 = objectMapper.readValue<DeltakerV2Dto>(value)
        val deltakerliste = deltakerlisteRepository.get(deltakerV2.deltakerlisteId).getOrThrow()
        val tiltakstype = deltakerliste.tiltak.arenaKode

        if (!unleashToggle.erKometMasterForTiltakstype(tiltakstype) && !unleashToggle.skalLeseArenaDeltakereForTiltakstype(tiltakstype)) {
            log.info("Ignorerer deltaker $key på tiltakstype $tiltakstype som ikke er støttet enda")
            return
        }

        val lagretDeltaker = deltakerService.get(deltakerV2.id).getOrNull()
        val deltakerFinnes = lagretDeltaker != null
        if (deltakerFinnes || deltakerV2.kilde == DeltakerV2Dto.Kilde.KOMET) {
            log.info("Oppdaterer deltaker med id ${deltakerV2.id}")
            deltakerService.oppdaterDeltaker(
                deltakeroppdatering = deltakerV2.toDeltakerOppdatering(),
            )
            vurderingService.upsert(deltakerV2.vurderingerFraArrangor.orEmpty())
            lagretDeltaker?.navBruker?.let {
                if (it.adresse == null) {
                    log.info("Oppdaterer navbruker som mangler adresse for deltakerid ${deltakerV2.id}")
                    navBrukerService.update(it.personident)
                }
            }
        } else {
            log.info("Inserter ny $tiltakstype deltaker med id ${deltakerV2.id}")
            val navBruker = navBrukerService.getOrCreate(deltakerV2.personalia.personident).getOrThrow()
            val deltaker = deltakerV2.toDeltaker(navBruker, deltakerliste)
            deltakerService.opprettArenaDeltaker(deltaker)
            vurderingService.upsert(deltakerV2.vurderingerFraArrangor.orEmpty())
        }
    }

    override fun start() = consumer.start()

    override suspend fun close() = consumer.close()
}
