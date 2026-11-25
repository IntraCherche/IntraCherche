package eu.intracherche.intracherche.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.time.Instant;
import java.util.*;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Service
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    /** "Everyone" — carried by many share ACLs, never returned in tokenGroups. */
    private static final String SID_EVERYONE = "S-1-1-0";
    /** "Authenticated Users" — the user has just bound, so they are one. */
    private static final String SID_AUTHENTICATED_USERS = "S-1-5-11";

    @Value("${spring.ldap.base}")
    private String ldapBase;

    private final LdapTemplate ldapTemplate;

    public SecurityService(LdapTemplate ldapTemplate) {
        this.ldapTemplate = ldapTemplate;
    }



    public Optional<String> getEmailForUser(String username) {
        try {
            List<String> emails = ldapTemplate.search(
                    query()
                            .base(ldapBase)
                            .where("sAMAccountName").is(username),
                    (Attributes attrs) -> {
                        Attribute mailAttr = attrs.get("mail");
                        try {
                            return mailAttr != null ? mailAttr.get().toString() : null;
                        } catch (javax.naming.NamingException e) {
                            return null;
                        }
                    }
            );
            return emails.stream().filter(Objects::nonNull).findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Every directory object whose sAMAccountName is {@code name} (AD compares it without regard
     * to case), with its SID and whether it is a group. Used once at startup to check the group
     * names of indexes.yaml's visibleToGroups. Unlike the per-request lookups, a directory
     * failure propagates: the caller refuses to start rather than guess who may see an index.
     */
    public List<DirectoryGroup> findBySamAccountName(String name) {
        return ldapTemplate.search(
                query()
                        .base(ldapBase)
                        .attributes("sAMAccountName", "objectSid", "objectClass")
                        .where("sAMAccountName").is(name),
                (Attributes attrs) -> {
                    Attribute classes = attrs.get("objectClass");
                    boolean group = false;
                    if (classes != null) {
                        NamingEnumeration<?> all = classes.getAll();
                        while (all.hasMore()) {
                            if ("group".equalsIgnoreCase(all.next().toString())) group = true;
                        }
                    }
                    Attribute sid = attrs.get("objectSid");
                    return new DirectoryGroup(
                            stringAttribute(attrs, "sAMAccountName"),
                            sid != null && sid.get() instanceof byte[] raw ? convertSidToString(raw) : null,
                            group);
                });
    }

    /** userAccountControl flag ACCOUNTDISABLE. */
    private static final int UAC_ACCOUNT_DISABLE = 0x2;
    /** accountExpires values meaning "never" (0 and the largest FILETIME). */
    private static final long FILETIME_NEVER = Long.MAX_VALUE;
    /** Milliseconds between 1601-01-01 (FILETIME epoch) and 1970-01-01. */
    private static final long FILETIME_EPOCH_OFFSET_MS = 11_644_473_600_000L;

    /** Self through the Spring proxy, so {@link #getEffectiveSids} goes through the cache of {@link #lookupUser}. */
    @Autowired
    @Lazy
    private SecurityService self;

    /**
     * Every SID that may appear in a file ACL for this user: their own, every security
     * group they are in (nested ones included), and the two well-known SIDs above.
     *
     * <p>Empty when the account may not hold a session (deleted, disabled, expired) — so the
     * background channels that act for a user (search alerts, Sherlock's queued questions) stop
     * reading documents for them too — and when the directory could not be read.
     */
    public List<String> getEffectiveSids(String username) {
        DirectoryUser user = (self != null ? self : this).lookupUser(username);
        if (!user.reachable() || user.denialReason(Instant.now()).isPresent()) {
            return new ArrayList<>();
        }
        return user.sids();
    }

    /** The accounts an attribute value points at, and whether the directory could be read at all. */
    public record AccountMatch(List<String> accounts, boolean reachable) {}

    private static final java.util.regex.Pattern ATTRIBUTE_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9-]*");

    /**
     * The user accounts whose {@code attribute} equals {@code value}, as their sAMAccountName. Used by the
     * single sign-on login to turn the provider's identity (an e-mail, a UPN) into the directory account.
     *
     * <p>At most two are read: one is a match, two is an ambiguity the caller must refuse. The value is
     * escaped by the query builder, so nothing in it can alter the filter; the attribute name comes from the
     * administrator's configuration and is checked all the same. Computer accounts are left out.
     */
    public AccountMatch findAccounts(String attribute, String value) {
        if (attribute == null || !ATTRIBUTE_NAME.matcher(attribute).matches()) {
            throw new IllegalArgumentException("Not a directory attribute name: " + attribute);
        }
        try {
            List<String> found = ldapTemplate.search(
                    query()
                            .base(ldapBase)
                            .countLimit(2)
                            .where("objectClass").is("user")
                            .and("objectClass").not().is("computer")
                            .and(attribute).is(value),
                    (Attributes attrs) -> {
                        Attribute account = attrs.get("sAMAccountName");
                        return account == null ? null : account.get().toString();
                    });
            return new AccountMatch(found.stream().filter(Objects::nonNull).distinct().toList(), true);
        } catch (Exception e) {
            log.error("Directory search for {} failed: {}", attribute, e.toString());
            return new AccountMatch(List.of(), false);
        }
    }

    /**
     * The user's SIDs and account state, in one LDAP round-trip (see {@link DirectoryUser}).
     *
     * <p>Cached because it is resolved on <em>every</em> authenticated request, not just at
     * login: the JWT carries only the subject, so {@code AuthTokenFilter} re-runs this for
     * each call. The cache TTL is therefore the window during which an AD change — group,
     * account disabled, password changed — is not yet reflected; see
     * {@code security.sidCacheTtlMinutes}.
     *
     * <p>Only an entry actually read is cached. A failure, or an account not found, is not: a
     * transient LDAP hiccup must not pin the user to an empty result set for the whole TTL.
     */
    // #p0, not #username: the build does not pass -parameters, so a parameter name resolves to null.
    @Cacheable(cacheNames = "userSids", key = "T(eu.intracherche.intracherche.security.DirectoryNames).of(#p0)",
            unless = "!#result.found()")
    public DirectoryUser lookupUser(String username) {
        // A visitor session is named "demo|<visitor>" (see DirectoryNames); the directory only knows "demo".
        username = DirectoryNames.of(username);
        try {

            // STEP 1: Find user Distinguished Name (DN)
            List<String> dns = ldapTemplate.search(
                    query()
                            .base(ldapBase)
                            .where("sAMAccountName").is(username),
                    (Attributes attrs) -> attrs.get("distinguishedName").get().toString()
            );

            if (dns.isEmpty()) {
                log.warn("No LDAP entry for sAMAccountName '{}' — user gets no SIDs", username);
                return DirectoryUser.notFound();
            }
            String userDn = dns.get(0);

            Set<String> sids = new LinkedHashSet<>();

            // STEP 2: One base-scope read for the user's own SID, the SIDs of every security
            // group in their token, and the account state. tokenGroups is computed by the DC and
            // already covers nesting, the primary group and domain-local/builtin groups, so this
            // single round-trip replaces a recursive memberOf walk that cost two round-trips
            // per group — each one a fresh LDAPS handshake on this unpooled context source.
            Attributes attrs = readUserEntry(userDn);
            boolean gotTokenGroups = addSidAndTokenGroups(attrs, sids);

            // STEP 3: Fall back to the walk when the DC did not return tokenGroups (it is a
            // constructed attribute: an RODC, a restricted bind account or a non-AD directory
            // may omit it). Slow, but a slow login beats a user who can see nothing.
            if (!gotTokenGroups) {
                log.warn("tokenGroups unavailable for '{}' — falling back to the recursive memberOf walk", username);
                resolveGroupsRecursive(userDn, sids, new HashSet<>());
            }

            // STEP 4: Add well-known SIDs
            sids.add(SID_EVERYONE);
            sids.add(SID_AUTHENTICATED_USERS);

            Long uac = longAttribute(attrs, "userAccountControl");
            return new DirectoryUser(List.copyOf(sids), true, true,
                    uac != null && (uac & UAC_ACCOUNT_DISABLE) != 0,
                    fileTimeToInstant(longAttribute(attrs, "accountExpires")),
                    stringAttribute(attrs, "pwdLastSet"));

        } catch (Exception e) {
            log.error("Critical LDAP Error resolving SIDs for user '{}'", username, e);
            return DirectoryUser.unreachable();
        }
    }

    /**
     * Reads {@code objectSid}, {@code tokenGroups} and the account state from the user entry in
     * one base-scope search. getAttributes() names the attributes explicitly, which is the only
     * way AD will compute and return tokenGroups.
     */
    private Attributes readUserEntry(String userDn) {
        return ldapTemplate.executeReadOnly(ctx -> ctx.getAttributes(userDn, new String[]{
                "objectSid", "tokenGroups", "userAccountControl", "accountExpires", "pwdLastSet"}));
    }

    /**
     * Adds the user's own SID and every tokenGroups SID found in {@code attrs} to {@code sids}.
     *
     * @return true if the DC returned a non-empty tokenGroups, i.e. the group set is complete.
     */
    private boolean addSidAndTokenGroups(Attributes attrs, Set<String> sids) throws NamingException {
        Attribute ownSid = attrs.get("objectSid");
        if (ownSid != null) {
            addSid(sids, (byte[]) ownSid.get());
        }

        Attribute tokenGroups = attrs.get("tokenGroups");
        if (tokenGroups == null || tokenGroups.size() == 0) {
            return false;
        }

        NamingEnumeration<?> all = tokenGroups.getAll();
        while (all.hasMore()) {
            addSid(sids, (byte[]) all.next());
        }
        return true;
    }

    private static String stringAttribute(Attributes attrs, String name) throws NamingException {
        Attribute attr = attrs.get(name);
        Object value = attr != null ? attr.get() : null;
        return value != null ? value.toString().trim() : null;
    }

    private static Long longAttribute(Attributes attrs, String name) throws NamingException {
        String value = stringAttribute(attrs, name);
        if (value == null || value.isEmpty()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Unreadable {} '{}' — ignored", name, value);
            return null;
        }
    }

    /** AD FILETIME (100 ns since 1601) to an Instant; null for "never" (0 or the maximum). */
    static Instant fileTimeToInstant(Long fileTime) {
        if (fileTime == null || fileTime <= 0 || fileTime == FILETIME_NEVER) return null;
        return Instant.ofEpochMilli(fileTime / 10_000 - FILETIME_EPOCH_OFFSET_MS);
    }

    /** Converts one binary SID and adds it, skipping anything malformed rather than failing the login. */
    private void addSid(Set<String> sids, byte[] raw) {
        String sid = convertSidToString(raw);
        if (sid != null) {
            sids.add(sid);
        }
    }


    /**
     * Fallback only — used when the DC does not return tokenGroups. Two LDAP round-trips per
     * group, recursively; see {@link #lookupUser} for the one-round-trip path.
     */
    private void resolveGroupsRecursive(String dn, Set<String> collectedSids, Set<String> visitedDns) {

        if (visitedDns.contains(dn)) return;
        visitedDns.add(dn);

        List<String> groupDns = getMemberOf(dn);

        for (String groupDn : groupDns) {

            // Get SID of the group
            String sid = getObjectSid(groupDn);
            if (sid != null) {
                collectedSids.add(sid);
            }

            // Recurse into parent groups
            resolveGroupsRecursive(groupDn, collectedSids, visitedDns);
        }
    }

    private List<String> getMemberOf(String dn) {

        return ldapTemplate.executeReadOnly(ctx -> {
            Attributes attrs = ctx.getAttributes(dn, new String[]{"memberOf"});

            List<String> result = new ArrayList<>();
            Attribute memberOf = attrs.get("memberOf");

            if (memberOf != null) {
                NamingEnumeration<?> all = memberOf.getAll();
                while (all.hasMore()) {
                    result.add(all.next().toString());
                }
            }

            return result;
        });
    }

    private String getObjectSid(String dn) {

        return ldapTemplate.executeReadOnly(ctx -> {
            Attributes attrs = ctx.getAttributes(dn, new String[]{"objectSid"});
            Attribute sidAttr = attrs.get("objectSid");

            if (sidAttr == null) return null;

            byte[] sidBytes = (byte[]) sidAttr.get();
            return convertSidToString(sidBytes);
        });
    }

    private String convertSidToString(byte[] sid) {

        // Header is 8 bytes (revision, sub-authority count, 6-byte authority) then 4 per
        // sub-authority. Anything shorter is not a SID; return null rather than index past it.
        if (sid == null || sid.length < 8) return null;
        int subAuthorityCount = sid[1] & 0xFF;
        if (sid.length < 8 + (subAuthorityCount * 4)) return null;

        StringBuilder strSid = new StringBuilder("S-");

        // Revision
        strSid.append(sid[0] & 0xFF);

        // Authority
        long authority = 0;
        for (int i = 2; i <= 7; i++) {
            authority |= (long) (sid[i] & 0xFF) << (8 * (5 - (i - 2)));
        }
        strSid.append("-").append(authority);

        // Sub-authorities
        for (int i = 0; i < subAuthorityCount; i++) {
            int offset = 8 + (i * 4);
            long subAuth = ((long) sid[offset] & 0xFF)
                    | (((long) sid[offset + 1] & 0xFF) << 8)
                    | (((long) sid[offset + 2] & 0xFF) << 16)
                    | (((long) sid[offset + 3] & 0xFF) << 24);

            strSid.append("-").append(subAuth);
        }

        return strSid.toString();
    }

//    private void extractSidsFromAttribute(Attribute attr, List<String> sids) throws NamingException {
//        if (attr == null) return;
//        for (int i = 0; i < attr.size(); i++) {
//            byte[] sidBytes = (byte[]) attr.get(i);
//            String sidString = convertToSidString(sidBytes);
//            if (sidString != null) sids.add(sidString);
//        }
//    }
//
//    private void processAttribute(Attribute attr, List<String> list) throws NamingException, NamingException {
//        if (attr == null) return;
//        for (int i = 0; i < attr.size(); i++) {
//            byte[] sidBytes = (byte[]) attr.get(i);
//            String sidString = convertToSidString(sidBytes); // Using the helper method provided earlier
//            if (sidString != null) list.add(sidString);
//        }
//    }
//
//    public String convertToSidString(byte[] sid) {
//        if (sid == null || sid.length < 8) return null;
//
//        StringBuilder sb = new StringBuilder("S-");
//
//        // Revision level (1 byte at offset 0)
//        int revision = sid[0];
//        sb.append(revision).append("-");
//
//        // Identifier Authority (6 bytes starting at offset 2)
//        long authority = 0;
//        for (int i = 2; i <= 7; i++) {
//            authority = (authority << 8) | (sid[i] & 0xFF);
//        }
//        sb.append(authority);
//
//        // Sub-authorities (count is at offset 1, each is 4 bytes)
//        int subAuthorityCount = sid[1];
//        for (int i = 0; i < subAuthorityCount; i++) {
//            int offset = 8 + (i * 4);
//            long subAuthority = 0;
//            // Little-endian conversion
//            for (int j = 0; j < 4; j++) {
//                subAuthority |= ((long) (sid[offset + j] & 0xFF) << (8 * j));
//            }
//            sb.append("-").append(subAuthority);
//        }
//
//        return sb.toString();
//    }
}
