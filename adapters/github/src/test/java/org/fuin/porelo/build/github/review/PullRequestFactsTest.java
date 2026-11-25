package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import org.fuin.porelo.forge.github.model.PullRequestReview;
import org.junit.jupiter.api.Test;

class PullRequestFactsTest {

    private static final String HEAD = "bb33e12342d6f5235c9c5acddfdb1fc6f86dedfd";

    private static final String OLDER = "aa33e12342d6f5235c9c5acddfdb1fc6f86dedfd";

    @Test
    void githubComsRestBaseNamesItsWebHost() {
        assertThat(PullRequestFacts.webHost(URI.create("https://api.github.com")))
                .isEqualTo("github.com");
        assertThat(PullRequestFacts.webHost(URI.create("https://git.example.org/api/v3")))
                .as("an Enterprise Server's REST base lies on its own host")
                .isEqualTo("git.example.org");
    }

    @Test
    void aRestBaseOfAnotherFormIsRefused() {
        for (final String api :
                List.of(
                        "http://api.github.com",
                        "https://user@api.github.com",
                        "https://api.github.com/x",
                        "https://git.example.org/api")) {
            assertThatThrownBy(() -> PullRequestFacts.webHost(URI.create(api)))
                    .as(api)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aPullRequestsUrlIsReadOnTheForgeTheRestBaseNames() {
        final PullRequestFacts.Address address =
                PullRequestFacts.address(
                        URI.create("https://api.github.com"),
                        "https://github.com/example-org/example-repo/pull/17");

        assertThat(address)
                .isEqualTo(new PullRequestFacts.Address("example-org", "example-repo", 17));
    }

    @Test
    void aPullRequestOnAnotherHostIsRefused() {
        for (final String url :
                List.of(
                        "https://evil.example/example-org/example-repo/pull/17",
                        "https://github.com/example-org/example-repo/pull/17?x=1",
                        "https://github.com/example-org/example-repo/issues/17",
                        "https://github.com/example-org/../pull/17",
                        "https://github.com/example-org/example-repo/pull/0",
                        "https://github.com/example-org/example-repo/pull/1234567890")) {
            assertThatThrownBy(
                            () ->
                                    PullRequestFacts.address(
                                            URI.create("https://api.github.com"), url))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theHeadIsApprovedWhenEveryReviewersLastVerdictOnItIsApproval() {
        assertThat(
                        PullRequestFacts.approved(
                                List.of(
                                        review(1, "CHANGES_REQUESTED", HEAD),
                                        review(1, "APPROVED", HEAD),
                                        review(2, "APPROVED", HEAD),
                                        review(2, "COMMENTED", HEAD)),
                                HEAD))
                .as("a later approval replaces a request for changes; a comment changes nothing")
                .isTrue();
    }

    @Test
    void aRequestForChangesOrNoVerdictOnTheHeadIsNotApproved() {
        assertThat(
                        PullRequestFacts.approved(
                                List.of(
                                        review(1, "APPROVED", HEAD),
                                        review(2, "CHANGES_REQUESTED", HEAD)),
                                HEAD))
                .isFalse();
        assertThat(PullRequestFacts.approved(List.of(review(1, "APPROVED", OLDER)), HEAD))
                .as("an approval of an older commit")
                .isFalse();
        assertThat(PullRequestFacts.approved(List.of(review(1, "COMMENTED", HEAD)), HEAD))
                .as("comments alone")
                .isFalse();
        assertThat(
                        PullRequestFacts.approved(
                                List.of(review(1, "APPROVED", HEAD), review(1, "DISMISSED", HEAD)),
                                HEAD))
                .as("a dismissed approval")
                .isFalse();
    }

    private static PullRequestReview review(long user, String state, String commit) {
        final ObjectNode node = (ObjectNode) ConversationTest.tree("conversation-review.json");
        ((ObjectNode) node.get("user")).put("id", user);
        node.put("state", state).put("commit_id", commit);
        return ConversationTest.JSON.convertValue(node, PullRequestReview.class);
    }
}
