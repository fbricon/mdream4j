//! Streaming golden dumper for differential testing.
//!
//! Reads JSONL `{"id","html","options","chunkSize"}` and writes
//! `{"id","streamed","oneshot","panicked"}`. `streamed` is every
//! `process_chunk` result concatenated with `finish()`; `chunkSize` is a UTF-8
//! byte count, advanced to the next character boundary like the Java probe.
//!
//! Usage:
//!   stream-golden [input.jsonl] > streamed.jsonl

use mdream::MarkdownStreamProcessor;
use mdream_reference::{convert_all, OptionsJson};
use serde::Deserialize;
use serde_json::Value;
use std::io::{self, BufRead, BufWriter, Write};

#[derive(Debug, Deserialize)]
struct Case {
  id: String,
  html: String,
  #[serde(default)]
  options: Value,
  #[serde(default = "default_chunk", rename = "chunkSize")]
  chunk_size: usize,
}

fn default_chunk() -> usize {
  1
}

fn main() -> io::Result<()> {
  let path = std::env::args().nth(1);
  let reader: Box<dyn BufRead> = match path {
    Some(path) => Box::new(io::BufReader::new(std::fs::File::open(path)?)),
    None => Box::new(io::BufReader::new(io::stdin())),
  };
  let stdout = io::stdout();
  let mut out = BufWriter::new(stdout.lock());
  std::panic::set_hook(Box::new(|_| {}));

  for line in reader.lines() {
    let line = line?;
    if line.trim().is_empty() {
      continue;
    }
    let case: Case = serde_json::from_str(&line)?;
    let options_json = serde_json::to_string(&case.options).expect("options always serialise");
    let options = OptionsJson::parse(&options_json).unwrap_or_default();
    let result = std::panic::catch_unwind(|| {
      let mut processor =
        MarkdownStreamProcessor::new_with_format(options.to_options(), options.to_format());
      let mut streamed = String::new();
      let mut start = 0;
      let html = case.html.as_str();
      while start < html.len() {
        let mut end = (start + case.chunk_size).min(html.len());
        while end < html.len() && !html.is_char_boundary(end) {
          end += 1;
        }
        streamed.push_str(&processor.process_chunk(&html[start..end]));
        start = end;
      }
      streamed.push_str(&processor.finish());
      let one = convert_all(&case.html, &options).markdown.markdown;
      (streamed, one)
    });
    let record = match result {
      Ok((streamed, one)) => serde_json::json!({
        "id": case.id,
        "streamed": streamed,
        "oneshot": one,
        "panicked": false,
      }),
      Err(_) => serde_json::json!({
        "id": case.id,
        "streamed": "<PANIC>",
        "oneshot": "<PANIC>",
        "panicked": true,
      }),
    };
    serde_json::to_writer(&mut out, &record)?;
    out.write_all(b"\n")?;
  }
  out.flush()
}
