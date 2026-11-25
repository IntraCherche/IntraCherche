package eu.intracherche.intracherche.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the directory says about a user at one moment: the SIDs that decide which documents they
 * may read, and whether their account may still hold a session at all.
 *
 * <p>Read in the same LDAP round-trip as the SIDs and cached with them (security.sidCacheTtlMinutes),
 * so a disabled, expired or deleted account, or a password change, ends the user's sessions within
 * that delay instead of when the token expires.
 *
 * @param found       false when no entry has this sAMAccountName (account deleted or renamed)
 * @param reachable   false when the directory could not be read: nothing is known, nothing is denied
 *                    on that basis, and the user gets no SIDs (sees no document) until it answers
 * @param pwdLastSet  AD's pwdLastSet, verbatim; null when the directory has no such attribute. A
 *                    session carries the value it saw at login, so any change — the user changing
 *                    their password, an admin resetting it or forcing a change at next logon — ends it
 */
public record DirectoryUser(List<String> sids, boolean found, boolean reachable, boolean disabled,
                            Instant accountExpires, String pwdLastSet) {

    static DirectoryUser notFound() {
        return new DirectoryUser(List.of(), false, true, false, null, null);
    }

    static DirectoryUser unreachable() {
        return new DirectoryUser(List.of(), false, false, false, null, null);
    }

    /** Why this account may not hold a session at {@code now}; empty when it may, or when unknown. */
    public Optional<String> denialReason(Instant now) {
        if (!reachable) return Optional.empty();
        if (!found) return Optional.of("compte introuvable dans l'annuaire");
        if (disabled) return Optional.of("compte désactivé");
        if (accountExpires != null && !now.isBefore(accountExpires)) return Optional.of("compte expiré");
        return Optional.empty();
    }
}
