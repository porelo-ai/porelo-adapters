# Adapter HTTP transport

This unpublished module supplies the HTTP transport and bounded build metadata readers shared by the forge adapters. It follows no redirects, can retain only the tail of a streamed log, and trusts the account's configured certificate authorities alongside the public roots as described in the [adapter documentation](../../doc/index.md#forgejo-actions).
`FailedTests` reads complete Surefire and Failsafe result blocks; `BuildFields` validates run IDs, triggers, and forge page URLs and limits the runs whose jobs are requested. It depends on the build API and holds no credentials.
