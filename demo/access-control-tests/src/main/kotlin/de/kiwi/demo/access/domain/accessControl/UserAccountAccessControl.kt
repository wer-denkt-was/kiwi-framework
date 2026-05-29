package de.kiwi.demo.access.domain.accessControl

import de.kiwi.api.domain.AccessControl
import de.kiwi.api.schema.FieldAccessControl
import de.kiwi.demo.access.TestUserAccountHolder
import de.kiwi.demo.access.domain.UserAccount
import de.kiwi.demo.access.domain.UserAccount_
import de.kiwi.demo.access.domain.schema.UserAccountSchema


/** Allow self-access to user-account for currently authenticated user */
val userAccountSelfAccess = AccessControl.where { account, user ->
    equal(account[UserAccount_.externalIdentity], user.identity.toString())
}

/** Allow access to user-account for operator of system associated with electricity-contract */
val userAccountOperatorAccess = accessControl<UserAccount> { accessedAccount, currentAccount ->
    val accessedContract = accessedAccount.join(UserAccount_.accessToElectricityContracts)
    val accessedSolarSystem = accessedContract.join(de.kiwi.demo.access.domain.ElectricityContract_.solarSystem)
    val accessedSolarOperator = accessedSolarSystem.join(de.kiwi.demo.access.domain.SolarSystem_.operator)
    val authorizedSolarOperator = currentAccount.join(UserAccount_.accessToSolarOperators)
    equal(accessedSolarOperator, authorizedSolarOperator)
}

val userAccountSameContractAccess = accessControl<UserAccount> { accessedAccount, currentAccount ->
    val accessedContract = accessedAccount.join(UserAccount_.accessToElectricityContracts)
    val authorizedContract = currentAccount.join(UserAccount_.accessToElectricityContracts)
    equal(accessedContract, authorizedContract)
}

/** Permission to read [UserAccount] */
val readUserAccount = userAccountSelfAccess or userAccountOperatorAccess or userAccountSameContractAccess

/** Allow field only for the currently authenticated user's own account */
val UserAccountSchema.selfOnlyFieldAccess: FieldAccessControl<UserAccount>
    get() = TestUserAccountHolder.fieldAccess(
        require = { add(id) },
        checkAccess = { context, account -> account.id == context[id] }
    )
