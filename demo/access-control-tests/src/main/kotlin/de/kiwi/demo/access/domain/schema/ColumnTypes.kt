package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.BasicColumnType
import de.kiwi.demo.access.domain.UserAccountRoles
import java.net.URI

/** ColumnType formatting/parsing a [URI] as text */
object UriColumnType : BasicColumnType<URI, String>() {
    override fun format(value: URI): String = value.toString()
    override fun parse(value: String): URI = URI.create(value)
}

/** ColumnType formatting/parsing a [UserAccountRoles] collection as a list of role-name strings */
object UserRolesType : BasicColumnType<UserAccountRoles, List<String>>() {
    override fun format(value: UserAccountRoles): List<String> = value.roleNames.toList()
    override fun parse(value: List<String>): UserAccountRoles = UserAccountRoles(value.toMutableSet())
}
