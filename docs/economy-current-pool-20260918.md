# Current native pool balance check

The change removes the immutable season snapshot from the active opening path.
It does not change key cost, bundle size, roll count, or a currency payout. The
current native reward set is recompiled on reload; a malformed reward is logged
and skipped, while the rest of the case remains available.

The JSON assessment models one item bundle per successfully debited key at
0/1/5 openings per player day. Vault and premium-token flows remain zero in this
change; item and voucher rewards are not assigned a SELL value. The calculator
must be run before delivery and the active reward count/logs checked after
reload on both managed servers.
