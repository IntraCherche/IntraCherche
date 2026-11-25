package eu.intracherche.intracherche.security;

/**
 * A directory object found by its sAMAccountName.
 *
 * @param name  the sAMAccountName as AD spells it
 * @param sid   its objectSid, null if it has none
 * @param group true for a group; false for a user, a computer or any other account
 */
public record DirectoryGroup(String name, String sid, boolean group) {}
