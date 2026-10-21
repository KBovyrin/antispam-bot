package ru.kbovyrin.antispam.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ParticipantRepositoryTest {

    @Autowired
    ParticipantRepository repository;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void savesAndFindsByChatAndUser() {
        Participant saved = repository.saveAndFlush(Participant.newPending(-100L, 42L, "ivan", java.time.Instant.now()));

        Optional<Participant> found = repository.findByChatIdAndUserId(-100L, 42L);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getStatus()).isEqualTo(Participant.Status.PENDING);
        assertThat(found.get().getUsername()).isEqualTo("ivan");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void chatAndUserPairIsUnique() {
        repository.saveAndFlush(Participant.newPending(-100L, 42L, "ivan", java.time.Instant.now()));

        // Существенный контракт: БД отклоняет дубликат пары (chat_id, user_id).
        assertThatThrownBy(() -> repository.saveAndFlush(Participant.newPending(-100L, 42L, "clone", java.time.Instant.now())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    @Test
    void sameUserInDifferentChatsIsAllowed() {
        repository.saveAndFlush(Participant.newPending(-100L, 42L, "ivan", java.time.Instant.now()));
        repository.saveAndFlush(Participant.newPending(-200L, 42L, "ivan", java.time.Instant.now()));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void statusChangeIsPersisted() {
        Participant participant = repository.saveAndFlush(Participant.newPending(-100L, 42L, "ivan", java.time.Instant.now()));

        participant.pass(java.time.Instant.now());
        repository.saveAndFlush(participant);
        entityManager.clear();

        assertThat(repository.findByChatIdAndUserId(-100L, 42L))
                .hasValueSatisfying(p -> assertThat(p.getStatus()).isEqualTo(Participant.Status.PASSED));
    }

    @Test
    void unknownUserIsAbsent() {
        assertThat(repository.findByChatIdAndUserId(-100L, 42L)).isEmpty();
    }

    @Test
    void findsBannedOnlyInWatchedChats() {
        Participant bannedWatched = Participant.newPending(-100L, 42L, "ivan", java.time.Instant.now());
        bannedWatched.ban(java.time.Instant.now());
        Participant pendingWatched = Participant.newPending(-100L, 43L, "petya", java.time.Instant.now());
        Participant bannedForeign = Participant.newPending(-999L, 44L, "spam", java.time.Instant.now());
        bannedForeign.ban(java.time.Instant.now());
        repository.saveAndFlush(bannedWatched);
        repository.saveAndFlush(pendingWatched);
        repository.saveAndFlush(bannedForeign);

        var banned = repository.findByChatIdInAndStatus(java.util.List.of(-100L), Participant.Status.BANNED);

        assertThat(banned).extracting(Participant::getUserId).containsExactly(42L);
    }

    @Test
    void findsBannedByUserAcrossWatchedChats() {
        Participant first = Participant.newPending(-100L, 42L, "ivan", java.time.Instant.now());
        first.ban(java.time.Instant.now());
        Participant second = Participant.newPending(-200L, 42L, "ivan", java.time.Instant.now());
        second.ban(java.time.Instant.now());
        Participant passed = Participant.newPending(-300L, 42L, "ivan", java.time.Instant.now());
        passed.pass(java.time.Instant.now());
        repository.saveAndFlush(first);
        repository.saveAndFlush(second);
        repository.saveAndFlush(passed);

        var banned = repository.findByUserIdAndChatIdInAndStatus(42L,
                java.util.List.of(-100L, -200L, -300L), Participant.Status.BANNED);

        assertThat(banned).extracting(Participant::getChatId).containsExactlyInAnyOrder(-100L, -200L);
    }

    @Test
    void emptyChatListYieldsNoResults() {
        // Фиксация поведения Hibernate 6 на пустом IN (GROUP_IDS может быть пустым):
        // пустой список наблюдения → никто не найден, а не ошибка/полный список
        repository.saveAndFlush(Participant.newPending(-100L, 42L, "ivan", java.time.Instant.now()));

        assertThat(repository.findByChatIdInAndStatus(java.util.List.of(), Participant.Status.BANNED)).isEmpty();
        assertThat(repository.findByUserIdAndChatIdInAndStatus(42L, java.util.List.of(),
                Participant.Status.BANNED)).isEmpty();
    }
}
