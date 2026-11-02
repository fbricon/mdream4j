CARGO ?= cargo

.PHONY: reference corpus goldens test clean

reference:
	$(CARGO) build --release --manifest-path tools/reference/Cargo.toml

corpus:
	node tools/corpus/extract.mjs

goldens: reference corpus
	$(CARGO) run --quiet --release --manifest-path tools/reference/Cargo.toml \
		--bin mdream-golden -- tools/corpus/corpus.jsonl > golden/corpus.jsonl

test: reference
	./mvnw -Pwith-reference verify

clean:
	$(CARGO) clean --manifest-path tools/reference/Cargo.toml
	./mvnw clean
