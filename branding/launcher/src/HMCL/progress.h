#pragma once

#include <string>

// Thin wrapper around the shell IProgressDialog that the launcher uses while
// downloading and unpacking the Java runtime. Every method is a no-op when the
// dialog could not be created, so callers never have to check for null.
class HLProgressDialog {
public:
  HLProgressDialog() = default;
  ~HLProgressDialog();

  HLProgressDialog(const HLProgressDialog &) = delete;
  HLProgressDialog &operator=(const HLProgressDialog &) = delete;

  // Creates and shows the dialog. cancelMessage is displayed after the user
  // presses the cancel button.
  void Start(const std::wstring &title, const std::wstring &cancelMessage);

  // Sets the first text line of the dialog.
  void SetLine(const std::wstring &line);

  // Updates the progress bar. A total of 0 keeps the bar indeterminate.
  void SetProgress(unsigned long long completed, unsigned long long total);

  [[nodiscard]] bool HasUserCancelled();

  void Stop();

private:
  void *dialog = nullptr;  // IProgressDialog *
};
