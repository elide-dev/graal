# JavaScript Using Completion Reproducers

Use upstream GraalJS baseline `934cb7cb826453f687220493bcf08fa386c659aa` or the candidate.
Both use the Graal import `29054f702c3cd8420cdc927409b7d0f55acddec2` declared in _graal-js/mx.graal-js/suite.py_.
Check that exact Graal revision out as the sibling _graal_ repository; do not update an unrelated existing checkout.
From the _graal-js_ suite with a matching `JAVA_HOME` and separate `MX_ALT_OUTPUT_ROOT` per revision, run:

```shell
mx build --build-logs=silent --dependencies TRUFFLE_JS_TESTS,GRAALJS_LAUNCHER,truffle:TRUFFLE_RUNTIME
mx js --experimental-options --js.explicit-resource-management=true \
  --js.unhandled-rejections=throw /path/to/reproduce.js
mx js --experimental-options --js.explicit-resource-management=true \
  --js.unhandled-rejections=throw /path/to/reproduce-async.js
```

The first baseline command fails with `Expected 42; got undefined` (exit 7).
The second independently isolates the asynchronous completion loss and fails with `value=undefined, disposed=1` (exit 7).
Both samples pass on the candidate and print a success message.
They use standard JavaScript APIs and do not need a Native Image bundle.

The expanded regression lives in _graal-js/src/com.oracle.truffle.js.test/js/explicit-resource-management/return_value.js_.
Run it through the existing suite with:

```shell
mx unittest com.oracle.truffle.js.test.suite.JSSimpleTestSuite
mx checkstyle --primary
```

The tested candidate passes all 487 tests in that suite.
