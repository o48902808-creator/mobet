# Runtime app and intervention boundaries

Workflows may bind allowed packages to exact installed version names with
`policy.packageVersions`. `PlanValidator` rejects bindings outside `allowedPackages`, and
`WorkflowRunner` compares the installed version again before every action. A mismatch or unknown
version halts before device mutation.

The accessibility service also treats unexpected human and system surfaces as safety events:

- click or scroll events not temporally attributable to a Mobet action stop the active run as user
  intervention;
- System UI and Android permission-controller windows stop the run immediately;
- accessibility interruption or service destruction cancels workflow and autonomous controllers;
- existing per-action package checks still reject every unexpected package transition.

The attribution grace window is deliberately short and begins immediately before a typed Mobet
action. It prevents Mobet's own resulting accessibility event from being mistaken for user input
without granting a long period in which concurrent user interaction could be ignored.
