// Run with --experimental-options --js.explicit-resource-management=true --js.unhandled-rejections=throw.
function check(expected, actual) {
    if (!Object.is(expected, actual)) throw new Error(`Expected ${expected}; got ${actual}`);
}
let disposed = 0;
function sync() {
    using resource = { [Symbol.dispose]() { disposed++; } };
    return 42;
}
check(42, sync());
check(1, disposed);
async function asyncCase() {
    await using resource = { async [Symbol.asyncDispose]() { await null; await null; disposed++; } };
    return 43;
}
asyncCase().then(value => {
    check(43, value);
    check(2, disposed);
    console.log('using-return reproducer passed');
});
