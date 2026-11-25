package com.example.elevservice

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA lager implementasjonen automatisk ved oppstart.
 * Vi får blant annet save(), findById() og findAll() uten å skrive kode.
 */
interface ElevRepository : JpaRepository<ElevEntity, UUID>
