package de.kiwi.demo.access.domain

data class UserAccountRoles(val roleNames: MutableSet<String>) : Iterable<String> by roleNames {
    companion object {
        const val ADMIN = "ADMIN"
        const val SOLAR_OPERATOR = "OPERATOR"
        const val SOLAR_INSTALLER = "INSTALLER"
        const val ELECTRICITY_CONTRACT = "CONTRACT"

        private const val DELIMITER = ","

        fun empty() = UserAccountRoles(HashSet())
        fun deserialize(roles: String) = UserAccountRoles(
            roles
                .split(DELIMITER)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableSet()
        )
    }

    fun grant(roleName: String) = roleNames.add(roleName)
    fun revoke(roleName: String) = roleNames.remove(roleName)

    fun modifyRoleGranted(roleName: String, granted: Boolean) =
        if (granted) grant(roleName)
        else revoke(roleName)

    fun serialize() = roleNames.joinToString(DELIMITER)
}
