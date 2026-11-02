CARGO ?= cargo

.PHONY: reference corpus goldens test clean bench bench-digests

reference:
	$(CARGO) build --release --manifest-path tools/reference/Cargo.toml

corpus:
	node tools/corpus/extract.mjs

goldens: reference corpus
	$(CARGO) run --quiet --release --manifest-path tools/reference/Cargo.toml \
		--bin mdream-golden -- tools/corpus/corpus.jsonl > golden/corpus.jsonl

bench:
	./tools/bench/run.sh

bench-digests: reference
	node tools/bench/digests.mjs

test: reference
	./mvnw -Pwith-reference verify

clean:
	$(CARGO) clean --manifest-path tools/reference/Cargo.toml
	./mvnw clean
