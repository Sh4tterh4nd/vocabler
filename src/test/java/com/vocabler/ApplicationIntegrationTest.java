package com.vocabler;

import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:vocabler-test;DB_CLOSE_DELAY=-1}", "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}"})
@AutoConfigureMockMvc
class ApplicationIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired LearningService learning;
    @Autowired CatalogController catalog;
    @Autowired CsvImportController csv;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;
    @Autowired SharingController sharing;
    private final java.security.Principal owner = () -> "alice";
    @org.springframework.boot.test.context.TestConfiguration
    static class FixedTime {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-20T12:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }
    long alice, bob, pairId, entryId;

    @BeforeEach void setup() {
        for (String table : new String[]{"review_ticket", "review_history", "learning_progress", "pair_share", "package_entry", "vocabulary_package", "vocabulary_entry", "language_pair", "app_user"}) db.update("delete from " + table);
        db.update("insert into app_user(username,password_hash) values (?,?)", "alice", encoder.encode("a-long-password"));
        db.update("insert into app_user(username,password_hash) values (?,?)", "bob", encoder.encode("another-password"));
        alice = learning.userId("alice"); bob = learning.userId("bob");
        catalog.createPair(owner, new CatalogController.PairInput("German", "Romanian"));
        pairId = db.queryForObject("select id from language_pair", Long.class);
        catalog.createEntry(owner, pairId, new CatalogController.EntryInput("Apfel", "măr", "der Apfel"));
        entryId = db.queryForObject("select id from vocabulary_entry", Long.class);
    }
    private LearningService.Queue next(long user, TranslationDirection direction) { return learning.next(user, pairId, direction, null); }
    private double score(long user, TranslationDirection direction) {
        return db.queryForObject("select score from learning_progress where user_id=? and entry_id=? and direction=?", Double.class, user, entryId, direction.name());
    }
    private void addWords(int count) { for (int i=0; i<count; i++) catalog.createEntry(owner, pairId, new CatalogController.EntryInput("source" + i, "target" + i, "")); }
    private MockMultipartFile file(String content) { return new MockMultipartFile("file", "words.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)); }
    private void setMastered(double dailyDecay) {
        next(alice, TranslationDirection.FORWARD);
        db.update("delete from review_ticket");
        var yesterday = clock.instant().atOffset(ZoneOffset.UTC).minusDays(1);
        db.update("update learning_progress set score=10, score_at=?, due_at=?, active=false, mastery_recall=true where user_id=?", yesterday, yesterday, alice);
        db.update("update app_user set daily_decay=? where id=?", dailyDecay, alice);
    }

    @Test void usersAndDirectionsHaveIndependentProgress() {
        var card = next(alice, TranslationDirection.FORWARD).card();
        assertEquals("Apfel", card.prompt()); assertEquals("măr", card.answer());
        learning.review(alice, card.ticket(), true);
        assertEquals("măr", next(alice, TranslationDirection.REVERSE).card().prompt());
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        next(bob, TranslationDirection.FORWARD);
        assertEquals(1, score(alice, TranslationDirection.FORWARD));
        assertEquals(0, score(alice, TranslationDirection.REVERSE));
        assertEquals(0, score(bob, TranslationDirection.FORWARD));
    }
    @Test void queueIsBoundedAndRepeatedLoadsReuseTheTicket() {
        addWords(104);
        var first = next(alice, TranslationDirection.FORWARD);
        assertEquals(75, first.activeCount());
        assertEquals(first.card().ticket(), next(alice, TranslationDirection.FORWARD).card().ticket());
        assertEquals(75, db.queryForObject("select count(*) from learning_progress", Integer.class));
    }
    @Test void graduatingAWordFreesAPlaceForAnother() {
        addWords(80);
        var first = next(alice, TranslationDirection.FORWARD).card();
        db.update("update learning_progress set score=7 where user_id=? and entry_id=?", alice, first.entryId());
        assertEquals(8, learning.review(alice, first.ticket(), true).after());
        assertFalse(db.queryForObject("select active from learning_progress where user_id=? and entry_id=?", Boolean.class, alice, first.entryId()));
        assertEquals(75, next(alice, TranslationDirection.FORWARD).activeCount());
        assertEquals(76, db.queryForObject("select count(*) from learning_progress", Integer.class));
    }
    @Test void masteredRecallRestoresTenAndCannotBeImmediatelyRepeated() {
        setMastered(0.2);
        var card = next(alice, TranslationDirection.FORWARD).card();
        assertTrue(card.maintenance()); assertTrue(card.score() < 10);
        assertEquals(10, learning.review(alice, card.ticket(), true).after());
        assertNull(next(alice, TranslationDirection.FORWARD).card());
    }
    @Test void missedMasteryClearsShortcutAndReentersLearning() {
        setMastered(0.2);
        var result = learning.review(alice, next(alice, TranslationDirection.FORWARD).card().ticket(), false);
        assertEquals(7.8, result.after(), 0.001); assertFalse(result.masteryRecall());
        assertTrue(db.queryForObject("select active from learning_progress where user_id=?", Boolean.class, alice));
    }
    @Test void scoreTenWithDecayDisabledIsNeverOffered() {
        setMastered(0);
        assertNull(next(alice, TranslationDirection.FORWARD).card());
    }
    @Test void ticketsCannotBeUsedByAnotherUserOrSubmittedTwice() {
        var card = next(alice, TranslationDirection.FORWARD).card();
        assertThrows(ResponseStatusException.class, () -> learning.review(bob, card.ticket(), true));
        learning.review(alice, card.ticket(), true);
        assertThrows(ResponseStatusException.class, () -> learning.review(alice, card.ticket(), true));
        assertEquals(1, score(alice, TranslationDirection.FORWARD));
    }
    @Test void expiredTicketsAreRejected() {
        var card = next(alice, TranslationDirection.FORWARD).card();
        db.update("update review_ticket set created_at=?", clock.instant().atOffset(ZoneOffset.UTC).minusHours(2));
        assertThrows(ResponseStatusException.class, () -> learning.review(alice, card.ticket(), true));
        assertEquals(0, score(alice, TranslationDirection.FORWARD));
    }
    @Test void selectedPackageCanEnrollWordsWhenAnotherListIsFull() {
        addWords(100); next(alice, TranslationDirection.FORWARD);
        catalog.createPackage(owner, pairId, new CatalogController.PackageInput("New words"));
        long packageId = db.queryForObject("select id from vocabulary_package", Long.class);
        long lastEntry = db.queryForObject("select max(id) from vocabulary_entry", Long.class);
        catalog.addToPackage(owner, pairId, packageId, lastEntry);
        var queue = learning.next(alice, pairId, TranslationDirection.FORWARD, packageId);
        assertEquals(lastEntry, queue.card().entryId());
        assertEquals(1, queue.activeCount());
    }
    @Test void shrinkingTheActiveLimitIsAppliedAtNextSelection() {
        addWords(100); next(alice, TranslationDirection.FORWARD);
        learning.saveSettings(alice, new LearningPolicy.Settings(10,8,1,2,0.2));
        assertEquals(10, next(alice, TranslationDirection.FORWARD).activeCount());
    }
    @Test void answeredWordsWaitUntilTheirNextReview() {
        learning.review(alice, next(alice, TranslationDirection.FORWARD).card().ticket(), false);
        assertNull(next(alice, TranslationDirection.FORWARD).card());
    }
    @Test void csvPreviewDoesNotWriteAndCommitHandlesUnicodeQuotesAndDuplicates() {
        var upload = file("\uFEFFsource,target,notes\nApfel,măr,duplicate\n\"Guten Morgen\",\"Bună dimineața\",\"hello, family\"\nGuten Morgen,Bună dimineața,same\n");
        var preview = csv.upload(owner, pairId, upload, false, null, TranslationDirection.FORWARD);
        assertEquals(1, preview.newEntries()); assertEquals(2, preview.duplicates()); assertEquals(0, preview.errors());
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
        csv.upload(owner, pairId, upload, true, null, TranslationDirection.FORWARD);
        assertEquals(2, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
        assertEquals("hello, family", db.queryForObject("select notes from vocabulary_entry where source_text='Guten Morgen'", String.class));
    }
    @Test void invalidCsvCommitIsAtomic() {
        var upload = file("source,target\nWasser,apă\nMissing,\n");
        assertEquals(1, csv.upload(owner, pairId, upload, false, null, TranslationDirection.FORWARD).errors());
        assertThrows(ResponseStatusException.class, () -> csv.upload(owner, pairId, upload, true, null, TranslationDirection.FORWARD));
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
    }
    @Test void csvCanAddExistingWordsToAPackage() {
        catalog.createPackage(owner, pairId, new CatalogController.PackageInput("Fruit"));
        long packageId = db.queryForObject("select id from vocabulary_package", Long.class);
        csv.upload(owner, pairId, file("source,target\nApfel,măr\n"), true, packageId, TranslationDirection.FORWARD);
        assertEquals(1, catalog.entries(owner, pairId, packageId).size());
    }
    @Test void packagesCannotContainEntriesFromOtherLanguagePairs() {
        catalog.createPair(owner, new CatalogController.PairInput("English", "French"));
        long otherPair = db.queryForObject("select max(id) from language_pair", Long.class);
        catalog.createPackage(owner, otherPair, new CatalogController.PackageInput("Other"));
        long packageId = db.queryForObject("select id from vocabulary_package", Long.class);
        assertThrows(ResponseStatusException.class, () -> catalog.addToPackage(owner, pairId, packageId, entryId));
    }
    @Test void authenticationCsrfAndValidationAreEnforced() throws Exception {
        mvc.perform(get("/api/pairs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(post("/api/pairs").with(user("alice")).contentType("application/json").content("{\"sourceLanguage\":\"English\",\"targetLanguage\":\"French\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/pairs").with(user("alice")).with(csrf()).contentType("application/json").content("{\"sourceLanguage\":\"\",\"targetLanguage\":\"French\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content("{\"username\":\"newuser\",\"password\":\"short\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content("{\"username\":\"newuser\",\"password\":\"safe-long-password\"}")).andExpect(status().isCreated());
        assertTrue(encoder.matches("safe-long-password", db.queryForObject("select password_hash from app_user where username='newuser'", String.class)));
        var login = mvc.perform(post("/api/auth/login").with(csrf()).param("username", "newuser").param("password", "safe-long-password")).andExpect(status().isNoContent()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("newuser"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "alice").param("password", "incorrect")).andExpect(status().isUnauthorized());
    }
    @Test void multipartPreviewAndCommitUseTheSameValidatedFormat() throws Exception {
        var upload = file("source,target,notes\nWasser,apă,water\n");
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(upload).param("commit", "false").with(user("alice")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.newEntries").value(1)).andExpect(jsonPath("$.committed").value(false));
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(upload).param("commit", "true").with(user("alice")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.committed").value(true));
        assertEquals(2, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
    }
    @Test void learningSettingsRejectInvalidLimitsWithoutSaving() throws Exception {
        mvc.perform(put("/api/learning/settings").with(user("alice")).with(csrf()).contentType("application/json")
            .content("{\"activeLimit\":9,\"graduationScore\":8,\"correctGain\":1,\"incorrectPenalty\":2,\"dailyDecay\":0.2}"))
            .andExpect(status().isBadRequest());
        assertEquals(75, learning.settings(alice).activeLimit());
    }
    @Test void learningApiUsesTheSignedInUser() throws Exception {
        mvc.perform(post("/api/learning/next").with(user("alice")).with(csrf()).contentType("application/json")
            .content("{\"pairId\":" + pairId + ",\"direction\":\"REVERSE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.card.prompt").value("măr"));
        assertEquals(1, db.queryForObject("select count(*) from learning_progress where user_id=?", Integer.class, alice));
        assertEquals(0, db.queryForObject("select count(*) from learning_progress where user_id=?", Integer.class, bob));
    }
    @Test void catalogIsPrivateByDefaultAcrossAllReadAndLearningEndpoints() throws Exception {
        mvc.perform(get("/api/pairs").with(user("bob"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/pairs").with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].IS_OWNER").value(true));
        mvc.perform(get("/api/pairs/" + pairId + "/entries").with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/pairs/" + pairId + "/packages").with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/pairs/" + pairId + "/shares").with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/learning/next").with(user("bob")).with(csrf()).contentType("application/json")
            .content("{\"pairId\":" + pairId + ",\"direction\":\"FORWARD\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(file("source,target\nsecret,word\n")).with(user("bob")).with(csrf()))
            .andExpect(status().isNotFound());
        assertEquals(0, db.queryForObject("select count(*) from learning_progress where user_id=?", Integer.class, bob));
    }
    @Test void onlySelectedUsersCanReadAndStudySharedVocabulary() throws Exception {
        db.update("insert into app_user(username,password_hash) values ('charlie','unused-test-hash')");
        mvc.perform(put("/api/pairs/" + pairId + "/shares").with(user("alice")).with(csrf()).contentType("application/json").content("{\"username\":\"BOB\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/pairs").with(user("bob"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].IS_OWNER").value(false)).andExpect(jsonPath("$[0].OWNER_USERNAME").value("alice"));
        mvc.perform(get("/api/pairs/" + pairId + "/entries").with(user("bob"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].TARGET_TEXT").value("măr"));
        mvc.perform(get("/api/pairs").with(user("charlie"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/pairs/" + pairId + "/entries").with(user("charlie"))).andExpect(status().isNotFound());
        learning.review(bob, next(bob, TranslationDirection.FORWARD).card().ticket(), true);
        next(alice, TranslationDirection.FORWARD);
        assertEquals(1, score(bob, TranslationDirection.FORWARD));
        assertEquals(0, score(alice, TranslationDirection.FORWARD));
    }
    @Test void sharingDoesNotGrantEditImportOrResharingPermission() throws Exception {
        catalog.createPackage(owner, pairId, new CatalogController.PackageInput("Fruit"));
        long packageId = db.queryForObject("select id from vocabulary_package", Long.class);
        catalog.addToPackage(owner, pairId, packageId, entryId);
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        mvc.perform(get("/api/pairs/" + pairId + "/packages").with(user("bob"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].NAME").value("Fruit"));
        mvc.perform(get("/api/pairs/" + pairId + "/entries").param("packageId", "" + packageId).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(post("/api/pairs/" + pairId + "/entries").with(user("bob")).with(csrf()).contentType("application/json").content("{\"sourceText\":\"new\",\"targetText\":\"word\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/pairs/" + pairId + "/packages").with(user("bob")).with(csrf()).contentType("application/json").content("{\"name\":\"New\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/pairs/" + pairId + "/packages/" + packageId + "/entries/" + entryId).with(user("bob")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/pairs/" + pairId + "/packages/" + packageId + "/entries/" + entryId).with(user("bob")).with(csrf())).andExpect(status().isForbidden());
        for (String commit : new String[]{"false","true"}) mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(file("source,target\nsecret,word\n")).param("commit", commit).with(user("bob")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/pairs/" + pairId + "/shares").with(user("bob"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/pairs/" + pairId + "/shares").with(user("bob")).with(csrf()).contentType("application/json").content("{\"username\":\"alice\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/pairs/" + pairId + "/shares/" + bob).with(user("bob")).with(csrf())).andExpect(status().isForbidden());
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from package_entry", Integer.class));
    }
    @Test void revocationRemovesAccessAndOutstandingTicketsButPreservesPersonalProgress() throws Exception {
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        learning.review(bob, next(bob, TranslationDirection.FORWARD).card().ticket(), true);
        var ticket = next(bob, TranslationDirection.REVERSE).card().ticket();
        mvc.perform(delete("/api/pairs/" + pairId + "/shares/" + bob).with(user("alice")).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/pairs").with(user("bob"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/pairs/" + pairId + "/entries").with(user("bob"))).andExpect(status().isNotFound());
        assertThrows(ResponseStatusException.class, () -> next(bob, TranslationDirection.FORWARD));
        assertThrows(ResponseStatusException.class, () -> learning.review(bob, ticket, true));
        assertEquals(1, score(bob, TranslationDirection.FORWARD));
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        assertEquals(1, score(bob, TranslationDirection.FORWARD));
        assertThrows(ResponseStatusException.class, () -> learning.review(bob, ticket, true));
        assertNotNull(next(bob, TranslationDirection.REVERSE).card());
    }
    @Test void reviewRechecksPermissionEvenIfATicketStillExists() {
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        var ticket = next(bob, TranslationDirection.FORWARD).card().ticket();
        db.update("delete from pair_share where pair_id=? and user_id=?", pairId, bob);
        assertThrows(ResponseStatusException.class, () -> learning.review(bob, ticket, true));
        assertEquals(0, score(bob, TranslationDirection.FORWARD));
    }
    @Test void eachUserCanOwnTheSameLanguagePairWithoutSharing() throws Exception {
        mvc.perform(post("/api/pairs").with(user("bob")).with(csrf()).contentType("application/json").content("{\"sourceLanguage\":\"German\",\"targetLanguage\":\"Romanian\",\"ownerUserId\":" + alice + "}"))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/pairs").with(user("bob"))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].OWNER_USERNAME").value("bob"));
        mvc.perform(get("/api/pairs").with(user("alice"))).andExpect(jsonPath("$.length()").value(1));
        catalog.createEntry(() -> "bob", db.queryForObject("select id from language_pair where owner_user_id=?", Long.class, bob), new CatalogController.EntryInput("Apfel", "măr", "Bob's private copy"));
        assertEquals(2, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
    }
    @Test void sharingIsIdempotentAndRejectsUnknownUsersOrSelf() throws Exception {
        for (String username : new String[]{"bob", "BOB"}) mvc.perform(put("/api/pairs/" + pairId + "/shares").with(user("alice")).with(csrf()).contentType("application/json").content("{\"username\":\"" + username + "\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/pairs/" + pairId + "/shares").with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].USERNAME").value("bob"));
        mvc.perform(put("/api/pairs/" + pairId + "/shares").with(user("alice")).with(csrf()).contentType("application/json").content("{\"username\":\"nobody\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/pairs/" + pairId + "/shares").with(user("alice")).with(csrf()).contentType("application/json").content("{\"username\":\"alice\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(1, db.queryForObject("select count(*) from pair_share", Integer.class));
    }
    @Test void sharingAndImportsStillRequireCsrf() throws Exception {
        mvc.perform(put("/api/pairs/" + pairId + "/shares").with(user("alice")).contentType("application/json").content("{\"username\":\"bob\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/pairs/" + pairId + "/shares/" + bob).with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(file("source,target\na,b\n")).with(user("alice"))).andExpect(status().isForbidden());
    }
    @Test void publicHealthProbesExposeStatusOnly() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test void reverseCsvNormalizesBeforePreviewDuplicatesAndPackageAssignment() throws Exception {
        catalog.createPackage(owner, pairId, new CatalogController.PackageInput("Imported"));
        long packageId = db.queryForObject("select id from vocabulary_package", Long.class);
        learning.review(alice, next(alice, TranslationDirection.FORWARD).card().ticket(), true);
        var upload = file("source, target,notes\nmăr,Apfel,keep original notes\nfrigider,\"Kühlschrank, Eisschrank\",\"kitchen, singular\"\nfrigider,\"Kühlschrank, Eisschrank\",duplicate\n");
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(upload)
                .param("direction", "REVERSE").param("packageId", "" + packageId)
                .with(user("alice")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.newEntries").value(1))
            .andExpect(jsonPath("$.duplicates").value(2)).andExpect(jsonPath("$.errors").value(0))
            .andExpect(jsonPath("$.rows[0].source").value("Apfel"))
            .andExpect(jsonPath("$.rows[0].target").value("măr"))
            .andExpect(jsonPath("$.rows[1].source").value("Kühlschrank, Eisschrank"))
            .andExpect(jsonPath("$.rows[1].target").value("frigider"))
            .andExpect(jsonPath("$.rows[1].notes").value("kitchen, singular"));
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from package_entry", Integer.class));
        assertTrue(csv.upload(owner, pairId, upload, true, packageId, TranslationDirection.REVERSE).committed());
        assertEquals(2, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
        assertEquals(2, catalog.entries(owner, pairId, packageId).size());
        assertEquals("kitchen, singular", db.queryForObject("select notes from vocabulary_entry where target_text='frigider'", String.class));
        assertEquals("der Apfel", db.queryForObject("select notes from vocabulary_entry where id=?", String.class, entryId));
        assertEquals(1, score(alice, TranslationDirection.FORWARD));
    }
    @Test void reverseCsvWorksWithEitherLanguagePairAndRejectsInvalidRowsAtomically() throws Exception {
        catalog.createPair(owner, new CatalogController.PairInput("German", "French"));
        long frenchPair = db.queryForObject("select id from language_pair where target_language='French'", Long.class);
        mvc.perform(multipart("/api/pairs/" + frenchPair + "/import")
                .file(file("source,target\nbonjour,Guten Tag\n")).param("direction", "REVERSE").param("commit", "true")
                .with(user("alice")).with(csrf())).andExpect(status().isOk());
        assertEquals("Guten Tag", db.queryForObject("select source_text from vocabulary_entry where pair_id=?", String.class, frenchPair));
        assertEquals("bonjour", db.queryForObject("select target_text from vocabulary_entry where pair_id=?", String.class, frenchPair));
        var invalid = file("source,target\npară,Birne\n,Fehlt\n");
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(invalid).param("direction", "REVERSE")
                .with(user("alice")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.errors").value(1));
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(invalid).param("direction", "REVERSE").param("commit", "true")
                .with(user("alice")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/pairs/" + pairId + "/import").file(file("source,target\na,b\n"))
                .param("direction", "SIDEWAYS").param("commit", "true").with(user("alice")).with(csrf()))
            .andExpect(status().isBadRequest());
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry where pair_id=?", Integer.class, pairId));
    }
    @Test void ownerDeletionCascadesAcrossPackagesUsersAndDirectionsAndInvalidatesCards() throws Exception {
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        catalog.createPackage(owner, pairId, new CatalogController.PackageInput("Fruit"));
        long packageId = db.queryForObject("select id from vocabulary_package", Long.class);
        catalog.addToPackage(owner, pairId, packageId, entryId);
        for (long userId : new long[]{alice, bob}) {
            learning.review(userId, next(userId, TranslationDirection.FORWARD).card().ticket(), true);
            next(userId, TranslationDirection.REVERSE);
        }
        String staleTicket = next(bob, TranslationDirection.REVERSE).card().ticket();
        assertEquals(4, db.queryForObject("select count(*) from learning_progress", Integer.class));
        assertEquals(2, db.queryForObject("select count(*) from review_history", Integer.class));
        assertEquals(2, db.queryForObject("select count(*) from review_ticket", Integer.class));
        mvc.perform(delete("/api/pairs/" + pairId + "/entries/" + entryId).with(user("alice")).with(csrf()))
            .andExpect(status().isNoContent());
        for (String table : new String[]{"vocabulary_entry", "package_entry", "learning_progress", "review_ticket", "review_history"})
            assertEquals(0, db.queryForObject("select count(*) from " + table, Integer.class), table);
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_package", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from language_pair", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from pair_share", Integer.class));
        mvc.perform(post("/api/learning/review").with(user("bob")).with(csrf()).contentType("application/json")
                .content("{\"ticket\":\"" + staleTicket + "\",\"correct\":true}"))
            .andExpect(status().isConflict());
        assertNull(next(alice, TranslationDirection.FORWARD).card());
        mvc.perform(delete("/api/pairs/" + pairId + "/entries/" + entryId).with(user("alice")).with(csrf()))
            .andExpect(status().isNotFound());
    }
    @Test void bulkDeletionIsAtomicAndCannotReachAnotherPair() throws Exception {
        catalog.createEntry(owner, pairId, new CatalogController.EntryInput("Birne", "pară", ""));
        long secondId = db.queryForObject("select id from vocabulary_entry where source_text='Birne'", Long.class);
        catalog.createPair(owner, new CatalogController.PairInput("English", "French"));
        long otherPair = db.queryForObject("select id from language_pair where source_language='English'", Long.class);
        catalog.createEntry(owner, otherPair, new CatalogController.EntryInput("apple", "pomme", ""));
        long otherId = db.queryForObject("select id from vocabulary_entry where pair_id=?", Long.class, otherPair);
        mvc.perform(delete("/api/pairs/" + pairId + "/entries").with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"entryIds\":[" + entryId + "," + otherId + "]}"))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/pairs/" + pairId + "/entries/" + otherId).with(user("alice")).with(csrf()))
            .andExpect(status().isNotFound());
        assertEquals(3, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
        mvc.perform(delete("/api/pairs/" + pairId + "/entries").with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"entryIds\":[" + entryId + "," + secondId + "," + entryId + "]}"))
            .andExpect(status().isNoContent());
        assertEquals(0, db.queryForObject("select count(*) from vocabulary_entry where pair_id=?", Integer.class, pairId));
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry where pair_id=?", Integer.class, otherPair));
    }
    @Test void deletionRequiresOwnershipAndCsrf() throws Exception {
        String payload = "{\"entryIds\":[" + entryId + "]}";
        mvc.perform(delete("/api/pairs/" + pairId + "/entries/" + entryId).with(user("bob")).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/pairs/" + pairId + "/entries").with(user("bob")).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isNotFound());
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        mvc.perform(delete("/api/pairs/" + pairId + "/entries/" + entryId).with(user("bob")).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/pairs/" + pairId + "/entries").with(user("bob")).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/pairs/" + pairId + "/entries/" + entryId).with(user("alice")))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/pairs/" + pairId + "/entries").with(user("alice")).contentType("application/json").content(payload))
            .andExpect(status().isForbidden());
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
    }
    @Test void emptyOrInvalidBulkDeleteRequestsCannotDeleteWords() throws Exception {
        for (String payload : new String[]{"{}", "{\"entryIds\":[]}", "{\"entryIds\":[null]}", "{\"entryIds\":[-1]}"})
            mvc.perform(delete("/api/pairs/" + pairId + "/entries").with(user("alice")).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isBadRequest());
        assertEquals(1, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
    }

    @Test void swappingPreservesNotesPackagesAndEveryUsersDirectionalProgressAndHistory() throws Exception {
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        catalog.createPackage(owner, pairId, new CatalogController.PackageInput("Fruit"));
        long packageId = db.queryForObject("select id from vocabulary_package", Long.class);
        catalog.addToPackage(owner, pairId, packageId, entryId);
        for (long userId : new long[]{alice, bob}) {
            learning.review(userId, next(userId, TranslationDirection.FORWARD).card().ticket(), true);
            next(userId, TranslationDirection.REVERSE);
        }
        db.update("update learning_progress set score=9.25, active=false, mastery_recall=true, score_at=?, due_at=? where user_id=? and direction='FORWARD'",
            clock.instant().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC), clock.instant().plus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC), alice);
        String staleTicket = next(bob, TranslationDirection.REVERSE).card().ticket();
        var beforeProgress = db.queryForList("select * from learning_progress");
        var beforeHistory = db.queryForList("select * from review_history");
        mvc.perform(post("/api/pairs/" + pairId + "/entries/swap").with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"entryIds\":[" + entryId + "]}"))
            .andExpect(status().isNoContent());
        var word = db.queryForMap("select * from vocabulary_entry where id=?", entryId);
        assertEquals("măr", word.get("SOURCE_TEXT"));
        assertEquals("Apfel", word.get("TARGET_TEXT"));
        assertEquals("der Apfel", word.get("NOTES"));
        assertEquals(1, catalog.entries(owner, pairId, packageId).size());
        for (var row : beforeProgress) {
            var expected = new java.util.HashMap<>(row);
            String opposite = row.get("DIRECTION").equals("FORWARD") ? "REVERSE" : "FORWARD";
            expected.put("DIRECTION", opposite);
            assertEquals(expected, db.queryForMap("select * from learning_progress where user_id=? and entry_id=? and direction=?", row.get("USER_ID"), entryId, opposite));
        }
        for (var row : beforeHistory) {
            var expected = new java.util.HashMap<>(row);
            expected.put("DIRECTION", row.get("DIRECTION").equals("FORWARD") ? "REVERSE" : "FORWARD");
            assertEquals(expected, db.queryForMap("select * from review_history where id=?", row.get("ID")));
        }
        assertEquals(4, db.queryForObject("select count(*) from learning_progress", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from review_ticket", Integer.class));
        mvc.perform(post("/api/learning/review").with(user("bob")).with(csrf()).contentType("application/json")
                .content("{\"ticket\":\"" + staleTicket + "\",\"correct\":true}"))
            .andExpect(status().isConflict());
        var correctedCard = next(bob, TranslationDirection.FORWARD).card();
        assertEquals("măr", correctedCard.prompt());
        assertEquals("Apfel", correctedCard.answer());
        assertNotEquals(staleTicket, correctedCard.ticket());
    }
    @Test void swappingSingleDirectionProgressTwiceRestoresOriginalWordsAndScores() throws Exception {
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        learning.review(alice, next(alice, TranslationDirection.FORWARD).card().ticket(), true);
        learning.review(bob, next(bob, TranslationDirection.REVERSE).card().ticket(), false);
        var before = db.queryForList("select * from learning_progress order by user_id,direction");
        String payload = "{\"entryIds\":[" + entryId + "," + entryId + "]}";
        mvc.perform(post("/api/pairs/" + pairId + "/entries/swap").with(user("alice")).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isNoContent());
        assertEquals(1, score(alice, TranslationDirection.REVERSE));
        assertEquals(0, score(bob, TranslationDirection.FORWARD));
        assertEquals(0, db.queryForObject("select count(*) from learning_progress where user_id=? and direction='FORWARD'", Integer.class, alice));
        assertEquals(0, db.queryForObject("select count(*) from learning_progress where user_id=? and direction='REVERSE'", Integer.class, bob));
        mvc.perform(post("/api/pairs/" + pairId + "/entries/swap").with(user("alice")).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isNoContent());
        assertEquals(before, db.queryForList("select * from learning_progress order by user_id,direction"));
        assertEquals("Apfel", db.queryForObject("select source_text from vocabulary_entry where id=?", String.class, entryId));
        assertEquals("măr", db.queryForObject("select target_text from vocabulary_entry where id=?", String.class, entryId));
    }
    @Test void swapConflictsAreAtomicAndSelectedReverseCounterpartsCanExchangePlaces() throws Exception {
        catalog.createEntry(owner, pairId, new CatalogController.EntryInput("măr", "Apfel", "reverse copy"));
        long reverseId = db.queryForObject("select id from vocabulary_entry where source_text='măr'", Long.class);
        catalog.createEntry(owner, pairId, new CatalogController.EntryInput("Birne", "pară", "other word"));
        long otherId = db.queryForObject("select id from vocabulary_entry where source_text='Birne'", Long.class);
        var before = db.queryForList("select * from vocabulary_entry order by id");
        String ticket = next(alice, TranslationDirection.FORWARD).card().ticket();
        mvc.perform(post("/api/pairs/" + pairId + "/entries/swap").with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"entryIds\":[" + entryId + "," + otherId + "]}"))
            .andExpect(status().isConflict());
        assertEquals(before, db.queryForList("select * from vocabulary_entry order by id"));
        assertEquals(1, db.queryForObject("select count(*) from review_ticket where id=?", Integer.class, ticket));
        mvc.perform(post("/api/pairs/" + pairId + "/entries/swap").with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"entryIds\":[" + entryId + "," + reverseId + "]}"))
            .andExpect(status().isNoContent());
        assertEquals("măr", db.queryForObject("select source_text from vocabulary_entry where id=?", String.class, entryId));
        assertEquals("Apfel", db.queryForObject("select target_text from vocabulary_entry where id=?", String.class, entryId));
        assertEquals("Apfel", db.queryForObject("select source_text from vocabulary_entry where id=?", String.class, reverseId));
        assertEquals("măr", db.queryForObject("select target_text from vocabulary_entry where id=?", String.class, reverseId));
        assertEquals("reverse copy", db.queryForObject("select notes from vocabulary_entry where id=?", String.class, reverseId));
        assertEquals("Birne", db.queryForObject("select source_text from vocabulary_entry where id=?", String.class, otherId));
        assertEquals(3, db.queryForObject("select count(*) from vocabulary_entry", Integer.class));
    }
    @Test void swappingRequiresOwnerCsrfValidIdsAndOnePair() throws Exception {
        String url = "/api/pairs/" + pairId + "/entries/swap";
        String payload = "{\"entryIds\":[" + entryId + "]}";
        mvc.perform(post(url).with(user("bob")).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isNotFound());
        sharing.share(owner, pairId, new SharingController.ShareInput("bob"));
        mvc.perform(post(url).with(user("bob")).with(csrf()).contentType("application/json").content(payload))
            .andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("alice")).contentType("application/json").content(payload))
            .andExpect(status().isForbidden());
        for (String invalid : new String[]{"{}", "{\"entryIds\":[]}", "{\"entryIds\":[null]}", "{\"entryIds\":[-1]}"})
            mvc.perform(post(url).with(user("alice")).with(csrf()).contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest());
        catalog.createPair(owner, new CatalogController.PairInput("English", "French"));
        long otherPair = db.queryForObject("select id from language_pair where source_language='English'", Long.class);
        catalog.createEntry(owner, otherPair, new CatalogController.EntryInput("apple", "pomme", ""));
        long otherId = db.queryForObject("select id from vocabulary_entry where pair_id=?", Long.class, otherPair);
        mvc.perform(post(url).with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"entryIds\":[" + entryId + "," + otherId + "]}"))
            .andExpect(status().isNotFound());
        assertEquals("Apfel", db.queryForObject("select source_text from vocabulary_entry where id=?", String.class, entryId));
        assertEquals("apple", db.queryForObject("select source_text from vocabulary_entry where id=?", String.class, otherId));
    }
}
