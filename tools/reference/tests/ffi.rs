use std::ffi::CStr;

fn take(ptr: *mut u8, len: usize) -> String {
  assert!(!ptr.is_null(), "conversion returned null");
  unsafe {
    let bytes = std::slice::from_raw_parts(ptr, len);
    let value = String::from_utf8_lossy(bytes).into_owned();
    mdream_reference::mdream_free(ptr, len);
    value
  }
}

#[test]
fn converts_through_the_c_abi() {
  let html = b"<h1>Hey Man</h1>";
  let options = b"{}";
  let mut len = 0usize;
  let mut truncated = 0u8;

  let ptr = unsafe {
    mdream_reference::mdream_convert(
      html.as_ptr(),
      html.len(),
      options.as_ptr(),
      options.len(),
      &mut len,
      &mut truncated,
    )
  };

  assert_eq!(take(ptr, len), "# Hey Man");
  assert_eq!(truncated, 0);
}

#[test]
fn streams_through_the_c_abi() {
  let options = b"{}";
  let stream = unsafe { mdream_reference::mdream_stream_new(options.as_ptr(), options.len()) };
  assert!(!stream.is_null());

  let mut len = 0usize;
  let mut out = String::new();
  for chunk in ["<h1>Hey ", "Man</h1><p>tail</p>"] {
    let ptr = unsafe {
      mdream_reference::mdream_stream_feed(stream, chunk.as_ptr(), chunk.len(), &mut len)
    };
    out.push_str(&take(ptr, len));
  }
  let mut truncated = 0u8;
  let ptr = unsafe { mdream_reference::mdream_stream_finish(stream, &mut len, &mut truncated) };
  out.push_str(&take(ptr, len));
  unsafe { mdream_reference::mdream_stream_free(stream) };

  assert_eq!(out, "# Hey Man\n\ntail");
}

#[test]
fn rejects_invalid_options() {
  let html = b"<p>x</p>";
  let options = b"not json";
  let mut len = 0usize;
  let mut truncated = 0u8;
  let ptr = unsafe {
    mdream_reference::mdream_convert(
      html.as_ptr(),
      html.len(),
      options.as_ptr(),
      options.len(),
      &mut len,
      &mut truncated,
    )
  };
  assert!(ptr.is_null());
}

// Keep the unused import out of the build in case the helper changes.
#[allow(dead_code)]
fn cstr(ptr: *const u8) -> String {
  unsafe { CStr::from_ptr(ptr as *const _).to_string_lossy().into_owned() }
}
