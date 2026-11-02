// Extracts HTML inputs from the upstream Rust test suites.
//
// The corpus stores inputs and options only; expected output comes from the
// reference bridge. This module therefore just needs to recover the strings the
// tests feed to the conversion APIs, plus the simple options they pass.
//
// Only literal arguments (directly or through a `let` in the same test) are
// extracted. Anything built with `format!`, `repeat`, or a loop variable is
// skipped: the reference dumper would accept it, but guessing its value here
// would silently generate the wrong case.

import fs from "node:fs";

const WS = new Set([" ", "\t", "\r", "\n"]);

function skipWs(src, index) {
  while (index < src.length && WS.has(src[index])) {
    index++;
  }
  return index;
}

/** Parses a Rust string literal starting at `index`, or returns null. */
function parseLiteral(src, index) {
  let i = skipWs(src, index);
  while (src[i] === "&" || src[i] === "(") {
    i = skipWs(src, i + 1);
  }
  if (src[i] === "r") {
    let j = i + 1;
    let hashes = 0;
    while (src[j] === "#") {
      hashes++;
      j++;
    }
    if (src[j] === '"') {
      const end = '"' + "#".repeat(hashes);
      const close = src.indexOf(end, j + 1);
      if (close < 0) {
        return null;
      }
      return { value: src.slice(j + 1, close), end: close + end.length };
    }
  }
  if (src[i] !== '"') {
    return null;
  }
  let out = "";
  let j = i + 1;
  while (j < src.length) {
    const c = src[j];
    if (c === '"') {
      return { value: out, end: j + 1 };
    }
    if (c !== "\\") {
      out += c;
      j++;
      continue;
    }
    const next = src[j + 1];
    const simple = {
      n: "\n",
      r: "\r",
      t: "\t",
      0: "\0",
      "\\": "\\",
      '"': '"',
      "'": "'",
    };
    if (next in simple) {
      out += simple[next];
      j += 2;
      continue;
    }
    if (next === "x") {
      out += String.fromCharCode(parseInt(src.slice(j + 2, j + 4), 16));
      j += 4;
      continue;
    }
    if (next === "u" && src[j + 2] === "{") {
      const close = src.indexOf("}", j + 3);
      out += String.fromCodePoint(parseInt(src.slice(j + 3, close), 16));
      j = close + 1;
      continue;
    }
    if (next === "\n") {
      j += 2;
      while (j < src.length && WS.has(src[j])) {
        j++;
      }
      continue;
    }
    out += next;
    j += 2;
  }
  return null;
}

/** Splits the arguments of a call whose `(` ends at `index`. */
function splitArguments(src, index) {
  const args = [];
  let current = "";
  let depth = 0;
  let i = index;
  while (i < src.length) {
    const c = src[i];
    if (c === '"') {
      const literal = parseLiteral(src, i);
      if (literal) {
        current += src.slice(i, literal.end);
        i = literal.end;
        continue;
      }
    }
    if (c === "'" && (src[i + 1] === "\\" || src[i + 2] === "'")) {
      let j = i + 1;
      while (j < src.length && src[j] !== "'") {
        j += src[j] === "\\" ? 2 : 1;
      }
      current += src.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    if (c === "/" && src[i + 1] === "/") {
      const newline = src.indexOf("\n", i);
      i = newline < 0 ? src.length : newline;
      continue;
    }
    if (c === "(" || c === "[" || c === "{") {
      depth++;
    } else if (c === ")" || c === "]" || c === "}") {
      if (depth === 0) {
        args.push(current.trim());
        return { args, end: i + 1 };
      }
      depth--;
    } else if (c === "," && depth === 0) {
      args.push(current.trim());
      current = "";
      i++;
      continue;
    }
    current += c;
    i++;
  }
  return { args, end: i };
}

function matchBrace(src, open) {
  let depth = 0;
  let i = open;
  while (i < src.length) {
    const c = src[i];
    if (c === '"') {
      const literal = parseLiteral(src, i);
      if (literal) {
        i = literal.end;
        continue;
      }
    }
    if (c === "/" && src[i + 1] === "/") {
      const newline = src.indexOf("\n", i);
      i = newline < 0 ? src.length : newline;
      continue;
    }
    if (c === "{") {
      depth++;
    } else if (c === "}") {
      depth--;
      if (depth === 0) {
        return i;
      }
    }
    i++;
  }
  return src.length;
}

/** Spans of `#[test]` function bodies. */
function testSpans(src) {
  const spans = [];
  const marker = /#\[test\]/g;
  let match;
  while ((match = marker.exec(src)) !== null) {
    const fn = /\bfn\s+\w+\s*\(/g;
    fn.lastIndex = match.index;
    const signature = fn.exec(src);
    if (!signature) {
      continue;
    }
    const open = src.indexOf("{", signature.index);
    if (open < 0) {
      continue;
    }
    const close = matchBrace(src, open);
    spans.push({ start: match.index, end: close });
    marker.lastIndex = close;
  }
  return spans;
}

/** Literal `const` string bindings declared at file level, available everywhere. */
function stringConstants(src) {
  const bindings = [];
  const re = /\bconst\s+(\w+)(?:\s*:[^=;]*)?\s*=\s*/g;
  let match;
  while ((match = re.exec(src)) !== null) {
    const literal = parseLiteral(src, re.lastIndex);
    if (literal) {
      bindings.push({ position: -1, name: match[1], value: literal.value });
    }
  }
  return bindings;
}

/** Literal `let` and local `const` bindings inside a span, in source order. */
function letBindings(src, span) {
  const bindings = [];
  const re = /\b(?:let|const)\s+(?:mut\s+)?(\w+)(?:\s*:[^=;]*)?\s*=\s*/g;
  re.lastIndex = span.start;
  let match;
  while ((match = re.exec(src)) !== null && match.index < span.end) {
    const literal = parseLiteral(src, re.lastIndex);
    if (literal) {
      bindings.push({ position: match.index, name: match[1], value: literal.value });
    }
  }
  return bindings;
}

/** Literal elements of a `[ "a", "b" ]` array starting at `open`, or null. */
function arrayElements(src, open) {
  const values = [];
  let i = open + 1;
  for (;;) {
    i = skipWs(src, i);
    if (src[i] === "]") {
      return { values, end: i + 1 };
    }
    const literal = parseLiteral(src, i);
    if (!literal) {
      return null;
    }
    values.push(literal.value);
    i = skipWs(src, literal.end);
    if (src[i] === ",") {
      i++;
      continue;
    }
    if (src[i] === "]") {
      return { values, end: i + 1 };
    }
    return null;
  }
}

/** Literal string-array constants in `src`, keyed by name. */
function arrayConstants(src, from, to) {
  const arrays = new Map();
  const re = /(?:\bconst|\blet)\s+(\w+)(?:\s*:[^=]*)?\s*=\s*&?\s*\[/g;
  re.lastIndex = from;
  let match;
  while ((match = re.exec(src)) !== null && match.index < to) {
    const array = arrayElements(src, re.lastIndex - 1);
    if (array) {
      arrays.set(match[1], array.values);
    }
  }
  return arrays;
}

/** `for <name> in <literal array | array constant> { ... }` loops inside a span. */
function loopsIn(src, span, arrays) {
  const loops = [];
  const re = /\bfor\s+&?\s*(\w+)\s+in\s+/g;
  re.lastIndex = span.start;
  let match;
  while ((match = re.exec(src)) !== null && match.index < span.end) {
    let i = skipWs(src, re.lastIndex);
    let values = null;
    if (src[i] === "[") {
      const array = arrayElements(src, i);
      if (array) {
        values = array.values;
        i = array.end;
      }
    } else {
      const identifier = /^(\w+)/.exec(src.slice(i));
      if (identifier && arrays.has(identifier[1])) {
        values = arrays.get(identifier[1]);
        i += identifier[1].length;
      }
    }
    if (!values) {
      continue;
    }
    const open = src.indexOf("{", i);
    if (open < 0 || open > span.end) {
      continue;
    }
    const close = matchBrace(src, open);
    loops.push({ name: match[1], values, start: open + 1, end: close });
    re.lastIndex = close;
  }
  return loops;
}

function resolveValue(argument, bindings, position) {
  let arg = argument.trim();
  for (;;) {
    const suffix = /\.(?:to_string|to_owned|as_str|to_str|into)\(\)$/;
    if (!suffix.test(arg)) {
      break;
    }
    arg = arg.replace(suffix, "");
  }
  const concat = /^concat!\s*\(/;
  if (concat.test(arg)) {
    const open = arg.indexOf("(");
    const { args } = splitArguments(arg, open + 1);
    const parts = args.map((part) => resolveValue(part, bindings, position));
    if (parts.some((part) => part == null)) {
      return null;
    }
    return parts.join("");
  }
  const literal = parseLiteral(arg, 0);
  if (literal) {
    return literal.value;
  }
  const identifier = /^&?\s*(\w+)$/.exec(arg);
  if (identifier) {
    let found = null;
    for (const binding of bindings) {
      if (binding.position < position && binding.name === identifier[1]) {
        found = binding.value;
      }
    }
    return found;
  }
  return null;
}

function resolveNumber(argument, bindings, position) {
  const value = resolveValue(argument, bindings, position);
  if (value != null && /^\d+$/.test(value.trim())) {
    return Number(value.trim());
  }
  if (/^\d+$/.test(argument.trim())) {
    return Number(argument.trim());
  }
  return null;
}

/** Calls to `name` inside a span, as `{ position, args }`. */
function callsIn(src, name, span) {
  const result = [];
  const re = new RegExp(`(?<![\\w.:])${name}\\s*\\(`, "g");
  re.lastIndex = span.start;
  let match;
  while ((match = re.exec(src)) !== null && match.index < span.end) {
    const { args, end } = splitArguments(src, re.lastIndex);
    result.push({ position: match.index, args });
    re.lastIndex = end;
  }
  return result;
}

function optionsFromExpression(expression, bindings, position) {
  const compact = expression.replace(/\s+/g, "");
  if (compact === "HtmlToMarkdownOptions::default()") {
    return {};
  }
  const wrap = /^HtmlToMarkdownOptions::default\(\)\.with_wrap_width\((.*)\)$/.exec(compact);
  if (wrap) {
    const width = /^\d+$/.test(wrap[1])
      ? Number(wrap[1])
      : resolveNumber(wrap[1], bindings, position);
    return width == null ? null : { wrapWidth: width };
  }
  return null;
}

/** Collects the cases one Rust file yields. */
function collectFile(path, idPrefix, helpers) {
  const src = fs.readFileSync(path, "utf8");
  const cases = [];
  const fileArrays = arrayConstants(src, 0, src.length);
  const fileStrings = stringConstants(src);
  let sequence = 0;
  const add = (html, options) => {
    if (html == null || options == null) {
      return;
    }
    sequence++;
    cases.push({
      id: `${idPrefix}-${String(sequence).padStart(3, "0")}`,
      html,
      options,
    });
  };
  for (const span of testSpans(src)) {
    const bindings = [...fileStrings, ...letBindings(src, span)];
    const arrays = new Map([...fileArrays, ...arrayConstants(src, span.start, span.end)]);
    for (const helper of helpers) {
      for (const call of callsIn(src, helper.name, span)) {
        const htmlIndex = helper.htmlIndex ?? 0;
        const html =
          call.args.length > htmlIndex
            ? resolveValue(call.args[htmlIndex], bindings, call.position)
            : null;
        if (html == null) {
          continue;
        }
        const options = helper.options
          ? helper.options(call, bindings)
          : helper.optionExpression != null
              ? optionsFromExpression(call.args[helper.optionExpression], bindings, call.position)
              : {};
        add(html, options);
      }
    }
    // A helper called with the loop variable applies to every literal element.
    for (const loop of loopsIn(src, span, arrays)) {
      for (const helper of helpers) {
        for (const call of callsIn(src, helper.name, loop)) {
          const htmlIndex = helper.htmlIndex ?? 0;
          if (call.args.length <= htmlIndex) {
            continue;
          }
          const isLoopVariable = new RegExp(`^&?\\s*${loop.name}$`).test(
            call.args[htmlIndex].trim(),
          );
          if (!isLoopVariable) {
            continue;
          }
          const options = helper.options
            ? helper.options(call, bindings)
            : helper.optionExpression != null
                ? optionsFromExpression(call.args[helper.optionExpression], bindings, call.position)
                : {};
          for (const html of loop.values) {
            add(html, options);
          }
        }
      }
    }
  }
  return cases;
}

function originOptions(call, bindings) {
  const origin = call.args.length > 1 ? resolveValue(call.args[1], bindings, call.position) : null;
  return origin == null ? null : { origin };
}

function wrapOptions(call, bindings) {
  const width = call.args.length > 1 ? resolveNumber(call.args[1], bindings, call.position) : null;
  return width == null ? null : { wrapWidth: width };
}

function cappedOptions(call, bindings) {
  const cap = call.args.length > 1 ? resolveNumber(call.args[1], bindings, call.position) : null;
  return cap == null ? null : { maxNodeBytes: cap };
}

export function collectRustCases(upstream) {
  const tests = `${upstream}/crates/core/tests`;
  const cases = [];
  cases.push(
    ...collectFile(`${tests}/conversion.rs`, "rust-conv", [
      { name: "convert" },
      { name: "convert_text" },
      { name: "convert_with_origin", options: originOptions },
      { name: "convert_text_with_origin", options: originOptions },
      { name: "html_to_markdown", optionExpression: 1 },
      { name: "html_to_text", optionExpression: 1 },
    ]),
  );
  cases.push(
    ...collectFile(`${tests}/implied_end_tags.rs`, "rust-implied", [{ name: "convert" }]),
  );
  cases.push(
    ...collectFile(`${tests}/whitespace_breaks.rs`, "rust-ws", [
      { name: "md" },
      { name: "text" },
      { name: "md_wrapped", options: wrapOptions },
    ]),
  );
  cases.push(
    ...collectFile(`${tests}/max_node_bytes.rs`, "rust-cap", [
      { name: "assert_capped_text", options: cappedOptions },
      { name: "assert_capped_inline_code", options: cappedOptions },
    ]),
  );
  cases.push(
    ...collectFile(`${tests}/streaming_drain.rs`, "rust-stream", [
      { name: "assert_stream_matches", optionExpression: 1 },
      { name: "assert_stream_matches_every_split", optionExpression: 1 },
      { name: "assert_text_stream_matches_every_split" },
    ]),
  );
  // Recovery and structure regressions: each file wraps a local `convert` that
  // applies the default options, so the literal argument is the whole input.
  const recovery = [
    ["end_tag_recovery.rs", "rust-endtag"],
    ["head_autoclose.rs", "rust-head"],
    ["select_option.rs", "rust-select"],
    ["template_parsing.rs", "rust-template"],
    ["templates.rs", "rust-templates"],
  ];
  for (const [file, prefix] of recovery) {
    cases.push(
      ...collectFile(`${tests}/${file}`, prefix, [
        { name: "convert" },
        { name: "html_to_markdown", optionExpression: 1 },
      ]),
    );
  }
  cases.push(
    ...collectFile(`${tests}/head_title.rs`, "rust-title", [
      { name: "html_to_markdown", optionExpression: 1 },
      { name: "html_to_markdown_result", optionExpression: 1 },
    ]),
  );
  cases.push(
    ...collectFile(`${tests}/fragment_link_offset.rs`, "rust-frag", [
      { name: "convert_fragments", options: () => ({ clean: { fragments: true } }) },
    ]),
  );
  cases.push(
    ...collectFile(`${tests}/streaming_html_parity.rs`, "rust-html-parity", [
      { name: "assert_stream_matches" },
    ]),
  );
  return cases;
}

