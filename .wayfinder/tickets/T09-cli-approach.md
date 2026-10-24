---
id: T09
title: Choose the CLI approach
type: grilling
status: open
assignee: null
blocked-by: [T01]
---

## Question

How is the CLI built and distributed: babashka script, babashka uberscript or `bbin` install, or GraalVM
native-image? What startup gate does CI enforce (tikka's was a 100 ms median)? Which page commands does it gain
(including the `$EDITOR` flow for pages)? The `cloisters mcp` stdio proxy is inherited and must fit the same
approach.
