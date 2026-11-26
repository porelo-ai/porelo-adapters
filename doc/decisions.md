# Decisions

What holds and why: one row per decision, and its full text below.

| Covers | What holds |
|---|---|
| Review conversion | [Review text is parsed, not modeled](#review-text-is-parsed-not-modeled) |
| Review conversion | [A fence that may not be what it seems is refused](#a-fence-that-may-not-be-what-it-seems-is-refused) |
| Review conversion | [What the conversion leaves unchecked](#what-the-conversion-leaves-unchecked) |
| Review conversion | [The review profile takes what GitHub takes](#the-review-profile-takes-what-github-takes) |
| Publishing | [Publish speaks to Artifactory with inline curl](#publish-speaks-to-artifactory-with-inline-curl) |
| Publishing | [The releases channel is unmeasured until a tag contains these steps](#the-releases-channel-is-unmeasured-until-a-tag-contains-these-steps) |

## Review text is parsed, not modeled

What is code, HTML or text in a comment is the answer of a CommonMark parser (`commonmark-java` with GitHub's tables and
footnotes), because a hand-built model kept reading cases otherwise than GitHub renders them.

- A write refuses an image, and a character reference in a link's destination or title or in a fence's info, which GitHub
  decodes.

## A fence that may not be what it seems is refused

- A fence indented by one to three spaces may sit in a list item the next line ends, and a fence after a line that may
  open an HTML block may not be a fence at all. A write refuses both rather than modeling lists.

## What the conversion leaves unchecked

Each is accepted because a wrong value here is refused or costs nothing; GitHub changing the behavior named would change
the answer.

- REST IDs are not checked to be positive, since GitHub numbers from 1 and a wrong one costs nothing here.
- `message.markdown` is never sent, so its length is held only by the document's limits.

## The review profile takes what GitHub takes

No bound of the conversion refuses a comment GitHub holds, since a held comment is lost from the review and leaves the
read incomplete.

- A body holds at most 65,536 characters, GitHub's own maximum, counted as Unicode scalar values on the body as GitHub
  holds it. The message's doubled braces do not count, so a body of 65,536 braces is a message of 131,072 characters.
- A line anchor may span any number of lines, and a suggestion may hold any number of lines of any length.
- A path has no length bound, because git has none: a commit with a path of 70,000 bytes is accepted by git 2.53 and
  passes `git fsck --strict`. Every other path check stays.
- The conversion bounds no whole document. A page of 100 comments of 65,536 braces holds 13 million characters of
  message, so the reader of a document must bound it above that.

## Publish speaks to Artifactory with inline curl

The job that holds the token downloads no client, so nothing fetched without a checksum runs beside it.

- The calls stay inline in `.github/workflows/build.yml` until a shared upload helper exists, since no repository keeps a
  copy of a helper another one has.
- `--proto =https` alone already stops a redirect to plain http (measured on curl 8.18.0), so a test of that guard removes
  `--proto` and `--proto-redir` both.

## The releases channel is unmeasured until a tag contains these steps

- **Exposure:** the release path of `Publish` - the refusal of a published release, the upload with
  `deb.distribution=releases`, the read-back of the releases index - has run only against a stand-in server, never against
  Artifactory. The snapshots channel has run against it, and the indexes list its four packages.
- **Why it stays:** only a tag runs that path, and a release is not made to measure it.
- **What would change the answer:** the first tag measures it. If that run fails or its index does not list the packages,
  this no longer holds.
