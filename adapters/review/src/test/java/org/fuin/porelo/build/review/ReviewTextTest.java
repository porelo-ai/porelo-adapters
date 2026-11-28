package org.fuin.porelo.build.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReviewTextTest {

    @Test
    void aForgejoLoginStartingWithAnUnderscoreBecomesACodeSpan() {
        assertThat(ReviewText.toForge("ask @_bot now"))
                .as("Forgejo notifies @_bot, which GitHub's login form would miss")
                .isEqualTo("ask `@_bot` now");
    }

    @Test
    void aForgejoLoginHoldingADotAndAnUnderscoreBecomesOneCodeSpan() {
        assertThat(ReviewText.toForge("see @a_b.c here"))
                .as("the whole login is spanned, so no shorter mention is left")
                .isEqualTo("see `@a_b.c` here");
    }

    @Test
    void aGitHubLoginStillBecomesACodeSpan() {
        assertThat(ReviewText.toForge("thanks @octo-cat"))
                .as("the wider form keeps GitHub's logins")
                .isEqualTo("thanks `@octo-cat`");
    }

    @Test
    void aSentencesFullStopAfterALoginStaysOutsideTheCodeSpan() {
        assertThat(ReviewText.toForge("ask @a_b."))
                .as("a login never ends in a dot, so the full stop is the sentence's")
                .isEqualTo("ask `@a_b`.");
    }

    @Test
    void aMentionSplitByEmphasisIsRefused() {
        assertThatThrownBy(() -> ReviewText.toForge("Ask @_bot_."))
                .as("Forgejo strips the emphasis and notifies @bot")
                .isInstanceOf(ReviewRefusal.class);
    }

    @Test
    void aMentionInsideEmphasisAfterAnotherIsRefused() {
        assertThatThrownBy(() -> ReviewText.toForge("Ask @_bot and @user_."))
                .as("the emphasis opened after the first at sign hides its login")
                .isInstanceOf(ReviewRefusal.class);
    }

    @Test
    void aMentionWithinStrongEmphasisStillBecomesACodeSpan() {
        assertThat(ReviewText.toForge("see **@octocat** here"))
                .as("emphasis around a whole mention keeps it escapable")
                .isEqualTo("see **`@octocat`** here");
    }

    @Test
    void aSentencesFullStopAfterATeamStaysOutsideTheCodeSpan() {
        assertThat(ReviewText.toForge("ask @org/team."))
                .as("a team name never ends in a dot, so the full stop is the sentence's")
                .isEqualTo("ask `@org/team`.");
    }

    @Test
    void anAddressIsNoMention() {
        assertThat(ReviewText.toForge("mail me at someone@example.org"))
                .as("an at sign after a letter mentions no one on either forge")
                .isEqualTo("mail me at someone@example.org");
    }
}
