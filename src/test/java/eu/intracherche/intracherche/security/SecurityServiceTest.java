package eu.intracherche.intracherche.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ldap.core.ContextExecutor;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Proves the SID resolution reads the whole group set in a single LDAP round-trip.
 *
 * <p>This is the guard on the bug that made IntraCherche unusable at a site with deep group
 * nesting: the previous walk issued two round-trips per group — each a fresh LDAPS handshake,
 * since the context source is unpooled — which took ~19 s and was aborted by the browser
 * before the response arrived. The count of {@code executeReadOnly} calls is the assertion
 * that matters here; a regression to the walk shows up as more than one.
 */
@ExtendWith(MockitoExtension.class)
class SecurityServiceTest {

    private static final String USER_DN = "CN=Alice,CN=Users,DC=corp,DC=local";

    // S-1-5-21-1111111111-2222222222-3333333333-1001 as AD returns it: revision, sub-authority
    // count, 6-byte authority, then little-endian 32-bit sub-authorities.
    private static final byte[] ALICE_SID = sid(1001);
    private static final byte[] GROUP_SID = sid(512);

    @Mock
    private LdapTemplate ldapTemplate;

    private SecurityService service;

    @BeforeEach
    void setUp() {
        service = new SecurityService(ldapTemplate);
        ReflectionTestUtils.setField(service, "ldapBase", "DC=corp,DC=local");
    }

    @Test
    @DisplayName("resolves the user's own SID and every tokenGroups SID in one round-trip")
    void readsTokenGroupsInOneRoundTrip() throws Exception {
        givenUserDnFound();

        BasicAttributes attrs = new BasicAttributes(true);
        attrs.put(new BasicAttribute("objectSid", ALICE_SID));
        BasicAttribute tokenGroups = new BasicAttribute("tokenGroups", GROUP_SID);
        tokenGroups.add(sid(513));
        attrs.put(tokenGroups);
        givenAttributes(attrs);

        List<String> sids = service.getEffectiveSids("alice");

        assertThat(sids).containsExactlyInAnyOrder(
                "S-1-5-21-1111111111-2222222222-3333333333-1001",
                "S-1-5-21-1111111111-2222222222-3333333333-512",
                "S-1-5-21-1111111111-2222222222-3333333333-513",
                "S-1-1-0",
                "S-1-5-11");

        // One read for objectSid+tokenGroups, and nothing else: no per-group walk.
        verify(ldapTemplate).executeReadOnly(any());
    }

    @Test
    @DisplayName("falls back to the memberOf walk when the DC returns no tokenGroups")
    void fallsBackWhenTokenGroupsAbsent() throws Exception {
        givenUserDnFound();

        BasicAttributes withoutTokenGroups = new BasicAttributes(true);
        withoutTokenGroups.put(new BasicAttribute("objectSid", ALICE_SID));

        // First read: no tokenGroups. Second: the walk asking this DN for its memberOf,
        // which is empty, so the walk terminates immediately.
        when(ldapTemplate.executeReadOnly(any()))
                .thenAnswer(inv -> execute(inv.getArgument(0), withoutTokenGroups))
                .thenAnswer(inv -> execute(inv.getArgument(0), new BasicAttributes(true)));

        List<String> sids = service.getEffectiveSids("alice");

        assertThat(sids).containsExactlyInAnyOrder(
                "S-1-5-21-1111111111-2222222222-3333333333-1001",
                "S-1-1-0",
                "S-1-5-11");
    }

    @Test
    @DisplayName("returns an empty list — never a partial one — when LDAP fails")
    void returnsEmptyOnFailure() {
        givenUserDnFound();
        when(ldapTemplate.executeReadOnly(any()))
                .thenThrow(new org.springframework.ldap.CommunicationException(
                        new javax.naming.CommunicationException("DC unreachable")));

        // A failure must not be cached (@Cacheable unless = "!#result.found()"), or a transient
        // blip pins the user to "sees nothing" for the TTL.
        assertThat(service.getEffectiveSids("alice")).isEmpty();
        assertThat(service.lookupUser("alice").found()).isFalse();
    }

    @Test
    @DisplayName("returns an empty list when the account does not exist, without touching the DC again")
    void returnsEmptyForUnknownUser() {
        when(ldapTemplate.search(any(org.springframework.ldap.query.LdapQuery.class),
                any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenReturn(List.of());

        assertThat(service.getEffectiveSids("ghost")).isEmpty();

        verify(ldapTemplate).search(any(org.springframework.ldap.query.LdapQuery.class),
                any(org.springframework.ldap.core.AttributesMapper.class));
        verifyNoMoreInteractions(ldapTemplate);
    }

    @Test
    @DisplayName("a visitor session name \"demo|<visitor>\" is looked up as the directory account \"demo\"")
    void visitorSessionReadsTheDirectoryAccount() {
        org.mockito.ArgumentCaptor<org.springframework.ldap.query.LdapQuery> query =
                org.mockito.ArgumentCaptor.forClass(org.springframework.ldap.query.LdapQuery.class);
        when(ldapTemplate.search(query.capture(), any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenReturn(List.of());

        service.lookupUser("demo|a3f9c2d41b7e0856");

        assertThat(query.getValue().filter().encode()).isEqualTo("(sAMAccountName=demo)");
    }

    @Test
    @DisplayName("findAccounts: searches user accounts (not computers) by the attribute, escaping the value")
    void findAccountsQuery() {
        org.mockito.ArgumentCaptor<org.springframework.ldap.query.LdapQuery> query =
                org.mockito.ArgumentCaptor.forClass(org.springframework.ldap.query.LdapQuery.class);
        when(ldapTemplate.search(query.capture(), any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenReturn(List.of("jdupont"));

        SecurityService.AccountMatch match = service.findAccounts("mail", "a*)(uid=*@corp.fr");

        assertThat(match.accounts()).containsExactly("jdupont");
        assertThat(match.reachable()).isTrue();
        String filter = query.getValue().filter().encode();
        assertThat(filter).startsWith("(&(objectClass=user)(!(objectClass=computer))(mail=")
                .doesNotContain("(uid=*").doesNotContain("a*)");
        assertThat(query.getValue().countLimit()).isEqualTo(2);
    }

    @Test
    @DisplayName("findAccounts: a directory failure is reported as unreachable, not as \"no account\"")
    void findAccountsUnreachable() {
        when(ldapTemplate.search(any(org.springframework.ldap.query.LdapQuery.class),
                any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenThrow(new org.springframework.ldap.CommunicationException(
                        new javax.naming.CommunicationException("DC unreachable")));

        SecurityService.AccountMatch match = service.findAccounts("mail", "jean@corp.fr");

        assertThat(match.reachable()).isFalse();
        assertThat(match.accounts()).isEmpty();
    }

    @Test
    @DisplayName("findAccounts: a value that is not an attribute name is rejected before any search")
    void findAccountsRejectsOddAttribute() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.findAccounts("mail)(cn=*", "x"));
        verifyNoMoreInteractions(ldapTemplate);
    }

    @Test
    @DisplayName("skips a malformed SID instead of failing the whole login")
    void skipsMalformedSid() throws Exception {
        givenUserDnFound();

        BasicAttributes attrs = new BasicAttributes(true);
        attrs.put(new BasicAttribute("objectSid", new byte[]{1, 2, 3})); // too short to be a SID
        attrs.put(new BasicAttribute("tokenGroups", GROUP_SID));
        givenAttributes(attrs);

        assertThat(service.getEffectiveSids("alice")).containsExactlyInAnyOrder(
                "S-1-5-21-1111111111-2222222222-3333333333-512",
                "S-1-1-0",
                "S-1-5-11");
    }

    @Test
    @DisplayName("reads the account state in the same round-trip as the SIDs")
    void readsAccountStateInTheSameRoundTrip() throws Exception {
        givenUserDnFound();
        BasicAttributes attrs = activeAlice();
        attrs.put(new BasicAttribute("pwdLastSet", "134050000000000000"));
        givenAttributes(attrs);

        DirectoryUser alice = service.lookupUser("alice");

        assertThat(alice.found()).isTrue();
        assertThat(alice.disabled()).isFalse();
        assertThat(alice.pwdLastSet()).isEqualTo("134050000000000000");
        assertThat(alice.denialReason(java.time.Instant.now())).isEmpty();
        verify(ldapTemplate).executeReadOnly(any());
    }

    @Test
    @DisplayName("a disabled account gets no SIDs and may not hold a session")
    void disabledAccount() throws Exception {
        givenUserDnFound();
        BasicAttributes attrs = activeAlice();
        attrs.put(new BasicAttribute("userAccountControl", "514")); // NORMAL_ACCOUNT | ACCOUNTDISABLE
        givenAttributes(attrs);

        assertThat(service.lookupUser("alice").denialReason(java.time.Instant.now())).contains("compte désactivé");
        assertThat(service.getEffectiveSids("alice")).isEmpty();
    }

    @Test
    @DisplayName("an account past its accountExpires date may not hold a session; 0 and the maximum mean never")
    void expiredAccount() throws Exception {
        givenUserDnFound();
        BasicAttributes attrs = activeAlice();
        attrs.put(new BasicAttribute("accountExpires", "133000000000000000")); // 2022-06-18
        givenAttributes(attrs);

        assertThat(service.lookupUser("alice").denialReason(java.time.Instant.now())).contains("compte expiré");
        assertThat(SecurityService.fileTimeToInstant(0L)).isNull();
        assertThat(SecurityService.fileTimeToInstant(Long.MAX_VALUE)).isNull();
        assertThat(SecurityService.fileTimeToInstant(116444736000000000L)).isEqualTo(java.time.Instant.EPOCH);
    }

    @Test
    @DisplayName("an account that no longer exists may not hold a session; an unreachable directory denies nothing")
    void notFoundVersusUnreachable() {
        when(ldapTemplate.search(any(org.springframework.ldap.query.LdapQuery.class),
                any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenReturn(List.of())
                .thenThrow(new org.springframework.ldap.CommunicationException(
                        new javax.naming.CommunicationException("DC unreachable")));

        assertThat(service.lookupUser("ghost").denialReason(java.time.Instant.now()))
                .contains("compte introuvable dans l'annuaire");
        DirectoryUser unknown = service.lookupUser("alice");
        assertThat(unknown.reachable()).isFalse();
        assertThat(unknown.denialReason(java.time.Instant.now())).isEmpty();
    }

    private static BasicAttributes activeAlice() {
        BasicAttributes attrs = new BasicAttributes(true);
        attrs.put(new BasicAttribute("objectSid", ALICE_SID));
        attrs.put(new BasicAttribute("tokenGroups", GROUP_SID));
        attrs.put(new BasicAttribute("userAccountControl", "512")); // NORMAL_ACCOUNT
        attrs.put(new BasicAttribute("accountExpires", "9223372036854775807"));
        return attrs;
    }

    // --- helpers ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void givenUserDnFound() {
        when(ldapTemplate.search(any(org.springframework.ldap.query.LdapQuery.class),
                any(org.springframework.ldap.core.AttributesMapper.class)))
                .thenReturn(List.of(USER_DN));
    }

    private void givenAttributes(Attributes attrs) {
        when(ldapTemplate.executeReadOnly(any()))
                .thenAnswer(inv -> execute(inv.getArgument(0), attrs));
    }

    /** Runs the production lambda against a DirContext that returns {@code attrs}. */
    @SuppressWarnings("unchecked")
    private static Object execute(ContextExecutor<?> executor, Attributes attrs) throws Exception {
        DirContext ctx = mock(DirContext.class);
        when(ctx.getAttributes(any(String.class), any(String[].class))).thenReturn(attrs);
        return executor.executeWithContext(ctx);
    }

    /** Builds S-1-5-21-1111111111-2222222222-3333333333-&lt;rid&gt; in AD's binary form. */
    private static byte[] sid(int rid) {
        int[] subAuthorities = {21, 1111111111, (int) 2222222222L, (int) 3333333333L, rid};
        byte[] out = new byte[8 + subAuthorities.length * 4];
        out[0] = 1;                                // revision
        out[1] = (byte) subAuthorities.length;     // sub-authority count
        out[7] = 5;                                // authority = 5 (NT Authority), big-endian
        for (int i = 0; i < subAuthorities.length; i++) {
            int value = subAuthorities[i];
            int offset = 8 + i * 4;
            out[offset] = (byte) value;            // little-endian
            out[offset + 1] = (byte) (value >>> 8);
            out[offset + 2] = (byte) (value >>> 16);
            out[offset + 3] = (byte) (value >>> 24);
        }
        return out;
    }
}
