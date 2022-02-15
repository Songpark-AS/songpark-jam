# songpark-jam

The jam session is the most complex part of the Songpark solution. This repo is
meant to isolate the part that handles the complexity on each environment of the
Songpark solution when in a jam session.

The different environments are TPX (on the Teleporter), Platform (our backend)
and App (our frontend).

## States

A jam session is very stateful. Look at the Songpark architecture documentation
for insights into how stateful. We wish to capture these states, formalize them
and how the interact, and then hook them up on each respective environment.
