//! Test-only reference bridge to the upstream Rust mdream core.
//!
//! This crate is never published and never loaded by the Java library. It
//! exists so Java tests and benchmarks can compare against the canonical
//! implementation.
//!
//! The C ABI is deliberately small: one-shot and streaming conversion over
//! UTF-8 buffers, with options passed as JSON. Errors surface as a null
//! pointer.

use mdream::{
  get_tag_id, html_to_format_result, CleanConfig, ExtractionConfig, FilterConfig,
  FrontmatterConfig, HtmlToMarkdownOptions, IsolateMainConfig, MarkdownStreamProcessor,
  OutputFormat, PluginConfig, TailwindConfig, TagOverrideConfig,
};
use serde::Deserialize;
use serde_json::Value;
use std::collections::BTreeMap;
use std::panic::{catch_unwind, AssertUnwindSafe};

// ---------------------------------------------------------------------------
// JSON options
// ---------------------------------------------------------------------------

/// JSON shape accepted by the bridge. Field names match the Java API.
#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct OptionsJson {
  pub origin: Option<String>,
  pub wrap_width: usize,
  pub max_node_bytes: usize,
  pub format: Option<String>,
  pub clean: Option<CleanJson>,
  pub plugins: Option<PluginsJson>,
}

#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CleanJson {
  pub urls: bool,
  pub fragments: bool,
  pub empty_links: bool,
  pub redundant_links: bool,
  pub self_link_headings: bool,
  pub empty_images: bool,
  pub empty_link_text: bool,
}

#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct PluginsJson {
  pub filter: Option<FilterJson>,
  pub isolate_main: bool,
  pub frontmatter: Option<FrontmatterJson>,
  pub tailwind: bool,
  pub extraction: Option<ExtractionJson>,
  pub tag_overrides: Option<BTreeMap<String, TagOverrideJson>>,
}

#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct FilterJson {
  pub include: Option<Vec<String>>,
  pub exclude: Option<Vec<String>>,
  pub process_children: Option<bool>,
}

#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct FrontmatterJson {
  pub additional_fields: Option<BTreeMap<String, String>>,
  pub meta_fields: Option<Vec<String>>,
}

#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ExtractionJson {
  pub selectors: Vec<String>,
}

#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct TagOverrideJson {
  pub enter: Option<String>,
  pub exit: Option<String>,
  pub spacing: Option<[u8; 2]>,
  pub is_inline: Option<bool>,
  pub is_self_closing: Option<bool>,
  pub collapses_inner_white_space: Option<bool>,
  pub alias: Option<String>,
}

impl OptionsJson {
  pub fn parse(json: &str) -> Result<Self, serde_json::Error> {
    if json.trim().is_empty() {
      return Ok(Self::default());
    }
    serde_json::from_str(json)
  }

  pub fn to_options(&self) -> HtmlToMarkdownOptions {
    HtmlToMarkdownOptions {
      origin: self.origin.clone(),
      clean: self.clean.as_ref().map(CleanJson::to_clean),
      plugins: self.plugins.as_ref().map(PluginsJson::to_plugins),
      wrap_width: self.wrap_width,
      max_node_bytes: self.max_node_bytes,
    }
  }

  pub fn to_format(&self) -> OutputFormat {
    match self.format.as_deref() {
      Some("text") => OutputFormat::Text,
      Some("html") => OutputFormat::Html,
      _ => OutputFormat::Markdown,
    }
  }
}

impl CleanJson {
  fn to_clean(&self) -> CleanConfig {
    CleanConfig {
      urls: self.urls,
      fragments: self.fragments,
      empty_links: self.empty_links,
      redundant_links: self.redundant_links,
      self_link_headings: self.self_link_headings,
      empty_images: self.empty_images,
      empty_link_text: self.empty_link_text,
    }
  }
}

impl PluginsJson {
  fn to_plugins(&self) -> PluginConfig {
    PluginConfig {
      filter: self.filter.as_ref().map(FilterJson::to_filter),
      isolate_main: self.isolate_main.then_some(IsolateMainConfig),
      frontmatter: self.frontmatter.as_ref().map(FrontmatterJson::to_frontmatter),
      tailwind: self.tailwind.then_some(TailwindConfig),
      extraction: self
        .extraction
        .as_ref()
        .map(|extraction| ExtractionConfig {
          selectors: extraction.selectors.clone(),
        }),
      tag_overrides: self.tag_overrides.as_ref().map(|overrides| {
        overrides
          .iter()
          .map(|(tag, config)| (tag.clone(), config.to_override()))
          .collect()
      }),
    }
  }
}

impl FilterJson {
  fn to_filter(&self) -> FilterConfig {
    FilterConfig {
      include: self.include.clone(),
      exclude: self.exclude.clone(),
      process_children: self.process_children,
    }
  }
}

impl FrontmatterJson {
  fn to_frontmatter(&self) -> FrontmatterConfig {
    FrontmatterConfig {
      additional_fields: self
        .additional_fields
        .as_ref()
        .map(|fields| fields.iter().map(|(k, v)| (k.clone(), v.clone())).collect()),
      meta_fields: self.meta_fields.clone(),
    }
  }
}

impl TagOverrideJson {
  fn to_override(&self) -> TagOverrideConfig {
    TagOverrideConfig {
      enter: self.enter.clone(),
      exit: self.exit.clone(),
      spacing: self.spacing,
      is_inline: self.is_inline,
      is_self_closing: self.is_self_closing,
      collapses_inner_white_space: self.collapses_inner_white_space,
      alias_tag_id: self.alias.as_deref().and_then(get_tag_id),
    }
  }
}

// ---------------------------------------------------------------------------
// Rust-level helpers used by the golden dumper
// ---------------------------------------------------------------------------

/// Converts one input in all three formats.
pub fn convert_all(input: &str, options: &OptionsJson) -> Converted {
  let base = options.to_options();
  Converted {
    markdown: html_to_format_result(input, base.clone(), OutputFormat::Markdown),
    text: html_to_format_result(input, base.clone(), OutputFormat::Text),
    html: html_to_format_result(input, base, OutputFormat::Html),
  }
}

pub struct Converted {
  pub markdown: mdream::MdreamResult,
  pub text: mdream::MdreamResult,
  pub html: mdream::MdreamResult,
}

impl Converted {
  /// Serialises the result for `golden/*.jsonl`.
  ///
  /// Frontmatter and extraction are recorded only when the plugin ran, so a case that does not
  /// request them keeps the exact same shape it had before the plugins were covered.
  pub fn to_json(&self) -> Value {
    let result = &self.markdown;
    let mut outputs = serde_json::json!({
      "markdown": result.markdown,
      "text": self.text.markdown,
      "html": self.html.markdown,
      "truncated": result.truncated,
    });
    let map = outputs.as_object_mut().expect("outputs is an object");
    if let Some(frontmatter) = &result.frontmatter {
      map.insert(
        "frontmatter".to_string(),
        Value::Array(
          frontmatter
            .iter()
            .map(|(key, value)| serde_json::json!([key, value]))
            .collect(),
        ),
      );
    }
    if let Some(extracted) = &result.extracted {
      map.insert(
        "extracted".to_string(),
        Value::Array(
          extracted
            .iter()
            .map(|element| {
              serde_json::json!({
                "selector": element.selector,
                "tagName": element.tag_name,
                "textContent": element.text_content,
                "attributes": element.attributes,
              })
            })
            .collect(),
        ),
      );
    }
    outputs
  }
}

// ---------------------------------------------------------------------------
// C ABI
// ---------------------------------------------------------------------------

fn to_raw(value: String) -> (*mut u8, usize) {
  let boxed: Box<[u8]> = value.into_bytes().into_boxed_slice();
  let len = boxed.len();
  (Box::into_raw(boxed) as *mut u8, len)
}

/// Frees a buffer returned by this library.
///
/// # Safety
/// `ptr` and `len` must come from a previous call to this library, and must not
/// be used again.
#[no_mangle]
pub unsafe extern "C" fn mdream_free(ptr: *mut u8, len: usize) {
  if ptr.is_null() {
    return;
  }
  drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr, len)));
}

unsafe fn as_str<'a>(ptr: *const u8, len: usize) -> &'a str {
  std::str::from_utf8_unchecked(std::slice::from_raw_parts(ptr, len))
}

/// Converts UTF-8 HTML with JSON options.
///
/// Returns a leaked UTF-8 buffer (free with `mdream_free`) and writes its
/// length to `out_len` and the truncated flag to `out_truncated`. Returns null
/// on invalid input or panic.
///
/// # Safety
/// Pointers must be valid for the given lengths; out pointers must be writable.
#[no_mangle]
pub unsafe extern "C" fn mdream_convert(
  html_ptr: *const u8,
  html_len: usize,
  options_ptr: *const u8,
  options_len: usize,
  out_len: *mut usize,
  out_truncated: *mut u8,
) -> *mut u8 {
  let result = catch_unwind(AssertUnwindSafe(|| {
    let html = as_str(html_ptr, html_len);
    let options_json = as_str(options_ptr, options_len);
    let parsed = OptionsJson::parse(options_json).ok()?;
    let options = parsed.to_options();
    let result = html_to_format_result(html, options, parsed.to_format());
    let truncated = result.truncated;
    std::ptr::write(out_truncated, u8::from(truncated));
    Some(result.markdown)
  }));

  match result {
    Ok(Some(markdown)) => {
      let (ptr, len) = to_raw(markdown);
      std::ptr::write(out_len, len);
      ptr
    }
    _ => {
      std::ptr::write(out_len, 0);
      std::ptr::write(out_truncated, 0);
      std::ptr::null_mut()
    }
  }
}

/// Opaque streaming handle.
pub struct MdreamStream {
  inner: MarkdownStreamProcessor,
}

/// Creates a streaming converter.
///
/// # Safety
/// `options_ptr` must be valid for `options_len` bytes. Returns null on a parse
/// failure.
#[no_mangle]
pub unsafe extern "C" fn mdream_stream_new(
  options_ptr: *const u8,
  options_len: usize,
) -> *mut MdreamStream {
  let result = catch_unwind(AssertUnwindSafe(|| {
    let parsed = OptionsJson::parse(as_str(options_ptr, options_len)).ok()?;
    Some(MdreamStream {
      inner: MarkdownStreamProcessor::new_with_format(parsed.to_options(), parsed.to_format()),
    })
  }));

  match result {
    Ok(Some(stream)) => Box::into_raw(Box::new(stream)),
    _ => std::ptr::null_mut(),
  }
}

/// Feeds one UTF-8 chunk. Returns a buffer to free with `mdream_free`.
///
/// # Safety
/// The stream must be live; pointers must be valid.
#[no_mangle]
pub unsafe extern "C" fn mdream_stream_feed(
  stream: *mut MdreamStream,
  chunk_ptr: *const u8,
  chunk_len: usize,
  out_len: *mut usize,
) -> *mut u8 {
  if stream.is_null() {
    std::ptr::write(out_len, 0);
    return std::ptr::null_mut();
  }
  let result = catch_unwind(AssertUnwindSafe(|| {
    let chunk = as_str(chunk_ptr, chunk_len);
    (*stream).inner.process_chunk(chunk)
  }));

  match result {
    Ok(markdown) => {
      let (ptr, len) = to_raw(markdown);
      std::ptr::write(out_len, len);
      ptr
    }
    Err(_) => {
      std::ptr::write(out_len, 0);
      std::ptr::null_mut()
    }
  }
}

/// Finishes the stream. Returns the remaining buffer to free with `mdream_free`.
///
/// # Safety
/// The stream must be live; out pointers must be writable.
#[no_mangle]
pub unsafe extern "C" fn mdream_stream_finish(
  stream: *mut MdreamStream,
  out_len: *mut usize,
  out_truncated: *mut u8,
) -> *mut u8 {
  if stream.is_null() {
    std::ptr::write(out_len, 0);
    std::ptr::write(out_truncated, 0);
    return std::ptr::null_mut();
  }
  let result = catch_unwind(AssertUnwindSafe(|| {
    let truncated = (*stream).inner.truncated();
    std::ptr::write(out_truncated, u8::from(truncated));
    (*stream).inner.finish()
  }));

  match result {
    Ok(markdown) => {
      let (ptr, len) = to_raw(markdown);
      std::ptr::write(out_len, len);
      ptr
    }
    Err(_) => {
      std::ptr::write(out_len, 0);
      std::ptr::write(out_truncated, 0);
      std::ptr::null_mut()
    }
  }
}

/// Releases a stream.
///
/// # Safety
/// `stream` must come from `mdream_stream_new` and not be used again.
#[no_mangle]
pub unsafe extern "C" fn mdream_stream_free(stream: *mut MdreamStream) {
  if !stream.is_null() {
    drop(Box::from_raw(stream));
  }
}
