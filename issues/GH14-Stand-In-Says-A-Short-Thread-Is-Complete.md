# GH14 — The Stand-In Says A Short Thread Is Complete

**Status:** Idea.

**What must be true.** A person looking at the stand-in's run sees it as partial whenever a review thread's comments were
not read to their end. Today `GitHubSource.comments` keeps what it has and reports nothing when GitHub answers a further page
with no node, or when the page limit stops it with more to come, so with every other list finished the run counts as
complete. Traced in the code, not yet reproduced by a test.
