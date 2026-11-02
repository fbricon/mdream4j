//! Golden corpus dumper.
//!
//! Reads JSONL cases `{"id": "...", "html": "...", "options": {...}}` and writes
//! the same cases with reference outputs for Markdown, text and HTML.
//!
//! Usage:
//!   mdream-golden [input.jsonl] > golden.jsonl

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
}

fn main() -> io::Result<()> {
  let path = std::env::args().nth(1);
  let reader: Box<dyn BufRead> = match path {
    Some(path) => Box::new(io::BufReader::new(std::fs::File::open(path)?)),
    None => Box::new(io::BufReader::new(io::stdin())),
  };

  let stdout = io::stdout();
  let mut out = BufWriter::new(stdout.lock());

  for (line_number, line) in reader.lines().enumerate() {
    let line = line?;
    if line.trim().is_empty() {
      continue;
    }
    let case: Case = serde_json::from_str(&line).map_err(|error| {
      io::Error::new(
        io::ErrorKind::InvalidData,
        format!("line {}: {error}", line_number + 1),
      )
    })?;

    let options_json =
      serde_json::to_string(&case.options).expect("options always serialise");
    let options = OptionsJson::parse(&options_json).unwrap_or_default();
    let converted = convert_all(&case.html, &options);

    let record = serde_json::json!({
      "id": case.id,
      "html": case.html,
      "options": case.options,
      "outputs": converted.to_json(),
    });
    serde_json::to_writer(&mut out, &record)?;
    out.write_all(b"\n")?;
  }

  out.flush()
}
