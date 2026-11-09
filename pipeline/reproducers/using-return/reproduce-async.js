// Run with --experimental-options --js.explicit-resource-management=true --js.unhandled-rejections=throw.
let disposed = 0;
async function run() {
    await using resource = { async [Symbol.asyncDispose]() { await null; await null; disposed++; } };
    return 43;
}
run().then(value => {
    if (value !== 43 || disposed !== 1) {
        throw new Error(`Async using lost its completion: value=${value}, disposed=${disposed}`);
    }
    console.log('async using-return reproducer passed');
});
