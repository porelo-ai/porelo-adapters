<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.4.1] - 2026-10-08
--------------------

### Added (2 changes)

- Build the reader on every push - compiled, tested and its native executable built and asked to describe itself 
- Read what GitHub Actions did with a commit for Sokar - the verdict across its runs, every job once it is settled and the last 64 KiB of each failed job's log or of every job's - and where a branch points, with the token Sokar hands over in each call and never on to the log's host 

### Changed (3 changes)

- Every build checks that each Maven module has a README.md linking its submodules, with sokar-release check-readmes from the build tools 
- The NullAway compile comes from sokar-parent, and exec-maven-plugin is 3.6.3 as it sets 
- The build takes org.fuin.sokar:sokar-parent as its parent, with every version it builds with unchanged 

### Fixed (1 change)

- The reader says GitHub's own message and what it asked when GitHub refuses the token (403), and the documentation names the two permissions it needs, Actions: Read and Contents: Read 



