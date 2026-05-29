package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.UserAccount
import de.kiwi.demo.access.domain.UserAccountId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface UserAccountRepository : JpaRepository<UserAccount, UserAccountId>, JpaSpecificationExecutor<UserAccount>
