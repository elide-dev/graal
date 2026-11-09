await using first={async [Symbol.asyncDispose](){await 0;throw 'dispose';}};
throw 'body';
