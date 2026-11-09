# Independent Reviews for the Next Graal Batch

The three selected fixes have two completed independent reviews each.
Each reviewer inspected the exact diff and specification in a fresh context before reading implementing evidence.
Public descriptions call these reviewers **high-power models**.
The reports retain the requested `gpt-6-astra` alias and invocation provenance; the tool does not expose an exact backend version, token usage or dollar cost.

| Candidate | Completed Reviews | Additional History |
| --- | --- | --- |
| Superclass flags | [First](superclass-first.json) · [Second](superclass-second.json) | Failed helper setup and build attempts remain in the reports |
| Continuation snapshot | [First completed](continuation-second.json) · [Replacement](continuation-replacement.json) | [Interrupted session](continuation-interrupted.json), which never produced approval |
| Crema without foreign API support | [First](crema-no-ffm-first.json) · [Second](crema-no-ffm-second.json) | Failed reflection and layer setup attempts remain in the reports |

The continuation behavior is specific to Native Image internal walks.
The change does not provide a new public HotSpot concurrent-walk contract, synchronize capture before the first frame, or atomically snapshot a whole parent chain.
Upstream permits AOT frames in this walk; the original fork's runtime compilation tethers are excluded.

Existing `ForeignTests` register a direct upcall.
The first Crema reviewer separately tested a runtime-created, unregistered upcall/downcall round trip, while the second tested an implementation type that was reachable but uninstantiated and an externally loaded class.

The monitor candidate was held after review found additional correctness problems.
The pod allocation optimization was held because synthetic allocation savings do not complete product qualification.
Neither is being filed upstream in this batch.

Reports contain concise findings, experiment commands, outputs and limitations, not private conversations or hidden reasoning.
Generated review executables and class files are omitted from this public source evidence tree; the retained probe sources and hashes support rebuilding them.
