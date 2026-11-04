# GH15 — An Overview Of Forge Functions

**Status:** Idea.
**Size:** S - one documentation page, filled from a table that is already read, and one test in the adapter modules.

**What must be true.** A person reads in one page of this repository's documentation, per function and forge (GitHub,
Forgejo, GitLab, Bitbucket Data Center), what the forge offers and what Porelo's adapter does today, and no cell claims
more than its source. It defines no new contract, adds no adapter call and waits on none.

## Plan

- A page under `doc/`, listed in `mkdocs.yml`, with one row per function and for each forge two facts: what the forge
  offers and what the adapter does today.
- Every "offers" cell keeps its source mark: **P** the call stands in the forge's API description at a named pin, **D** read
  only in the forge's public documentation, **K** known from memory and read nowhere, **R** also seen in recorded answers.
  The page says what each mark means and names each pin and its digest; a description with no published digest says so.
- The content comes from the reading of the four forges' API descriptions by `porelo-forge-clients`, whose agent holds it.
  Nothing is called against a forge for this page, and an unsettled point stays marked as unsettled.
- The "adapter does today" cells state only what this repository serves: `org.fuin.porelo.Build2` for GitHub and Forgejo,
  with no adapter for GitLab or Bitbucket Data Center. A planned function is shown as not done.
- **How it stays true:** the adapter cells by a test tagged `documents`, red first if a method an adapter serves has no row or
  a row claims one it does not serve; the "offers" cells by no test, since nothing cheap can ask four forges, so their marks and
  pins say how far each is to be believed.

## Acceptance

- The page builds under `check-doc-site` and is reachable from the documentation's navigation.
- Every "offers" cell carries a mark, and every pin named has its digest or says it has none.
- The test is seen to fail on a method removed from the page and on a row claiming a method no adapter serves.

## To be checked

- How the test learns what an adapter serves: the `Build2` interface description on its class path, or what the adapter itself
  answers.
- That the source holds nothing a public page may not: no internal issue number, account or machine.
