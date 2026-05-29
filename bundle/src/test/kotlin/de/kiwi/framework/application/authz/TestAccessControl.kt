package de.kiwi.framework.application.authz

import de.kiwi.api.domain.AccessControlBuilder

val accessControl = AccessControlBuilder.byUserRelation<TestUser> { userRoot, user ->
    equal(userRoot[TestUser_.id], user.userId)
}

val testParentAccess = accessControl<TestParent> { parent, user ->
    // User.accesses -< Access.granted -> Parent
    val access = user.join(TestUser_.accesses)
    equal(parent, access[TestAccess_.granted])
}

val testChildAccess = accessControl<TestChild> { child, user ->
    // User.accesses -< Access[allowChildren=true].granted -> Parent <- Child.parent
    val parent = child.join(TestChild_.parent)
    val access = user.join(TestUser_.accesses)
    and(
        equal(parent, access[TestAccess_.granted]),
        isTrue(access[TestAccess_.allowChildren]),
    )
}
