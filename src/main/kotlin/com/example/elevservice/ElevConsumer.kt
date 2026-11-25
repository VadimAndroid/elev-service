package com.example.elevservice

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class ElevConsumer(
    private val elevRepository: ElevRepository,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(ElevConsumer::class.java)

    @KafkaListener(topics = ["\${elev.kafka.topic}"])
    fun motta(melding: String) {
        val elev = jsonMapper.readValue(melding, Elev::class.java)
        // ID-en fra Kafka er primærnøkkel. Finnes den fra før, oppdaterer save()
        // den eksisterende raden i stedet for å lage en ny -> ingen duplikater.
        elevRepository.save(ElevEntity.fra(elev))
        log.info("Lagret elev med id {} i databasen", elev.id)
    }
}
