use mdream::splitter::{SplitterOptions, split_markdown};
use std::io::{self, BufRead, Write};

fn main() {
  let stdin = io::stdin();
  let stdout = io::stdout();
  let mut out = stdout.lock();
  for line in stdin.lock().lines() {
    let line = line.unwrap();
    if line.trim().is_empty() {
      continue;
    }
    let value: serde_json::Value = serde_json::from_str(&line).unwrap();
    let markdown = value["markdown"].as_str().unwrap();
    let o = &value["options"];

    let mut opts = SplitterOptions::default();
    if let Some(levels) = o.get("headersToSplitOn").and_then(|x| x.as_array()) {
      opts.headers_to_split_on =
        levels.iter().map(|x| x.as_u64().unwrap() as u8).collect();
    }
    if let Some(x) = o.get("returnEachLine").and_then(|x| x.as_bool()) {
      opts.return_each_line = x;
    }
    if let Some(x) = o.get("stripHeaders").and_then(|x| x.as_bool()) {
      opts.strip_headers = x;
    }
    if let Some(x) = o.get("chunkSize").and_then(|x| x.as_u64()) {
      opts.chunk_size = x as usize;
    }
    if let Some(x) = o.get("chunkOverlap").and_then(|x| x.as_u64()) {
      opts.chunk_overlap = x as usize;
    }

    let result: Vec<serde_json::Value> = split_markdown(markdown, &opts)
      .into_iter()
      .map(|chunk| {
        let loc = chunk.metadata.loc;
        serde_json::json!({
          "content": chunk.content,
          "headers": chunk.metadata.headers,
          "code": chunk.metadata.code,
          "from": loc.as_ref().map(|l| l.from),
          "to": loc.as_ref().map(|l| l.to),
        })
      })
      .collect();
    writeln!(out, "{}", serde_json::to_string(&result).unwrap()).unwrap();
  }
}
