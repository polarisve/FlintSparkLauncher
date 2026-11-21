#include <windows.h>
#include <shlobj.h>

#include "progress.h"

namespace {

// CLSID_ProgressDialog / IID_IProgressDialog are declared in shlguid.h which is
// not pulled in by shlobj.h on every SDK version, and depending on uuid.lib for
// two well-known GUIDs is unnecessary. Mirror the definitions locally instead.
const GUID kProgressDialogClsid = {0xF8383852,
                                   0xFCD3,
                                   0x11D1,
                                   {0xA6, 0xB9, 0x00, 0x60, 0x97, 0xDF, 0x5B, 0xD4}};

const GUID kProgressDialogIid = {0xEBBC7C04,
                                 0x315E,
                                 0x11D2,
                                 {0xB6, 0x2F, 0x00, 0x60, 0x97, 0xDF, 0x5B, 0xD4}};

}  // namespace

HLProgressDialog::~HLProgressDialog() { Stop(); }

void HLProgressDialog::Start(const std::wstring &title, const std::wstring &cancelMessage) {
  if (dialog != nullptr) {
    return;
  }

  IProgressDialog *progressDialog = nullptr;
  if (FAILED(CoCreateInstance(kProgressDialogClsid, nullptr, CLSCTX_INPROC_SERVER, kProgressDialogIid,
                              reinterpret_cast<void **>(&progressDialog))) ||
      progressDialog == nullptr) {
    return;
  }

  progressDialog->SetTitle(title.c_str());
  progressDialog->SetCancelMsg(cancelMessage.c_str(), nullptr);
  progressDialog->StartProgressDialog(nullptr, nullptr, PROGDLG_MODAL | PROGDLG_AUTOTIME | PROGDLG_NOMINIMIZE,
                                     nullptr);

  dialog = progressDialog;
}

void HLProgressDialog::SetLine(const std::wstring &line) {
  if (dialog == nullptr) {
    return;
  }

  static_cast<IProgressDialog *>(dialog)->SetLine(1, line.c_str(), FALSE, nullptr);
}

void HLProgressDialog::SetProgress(unsigned long long completed, unsigned long long total) {
  if (dialog == nullptr) {
    return;
  }

  static_cast<IProgressDialog *>(dialog)->SetProgress64(completed, total);
}

bool HLProgressDialog::HasUserCancelled() {
  if (dialog == nullptr) {
    return false;
  }

  return static_cast<IProgressDialog *>(dialog)->HasUserCancelled() == TRUE;
}

void HLProgressDialog::Stop() {
  if (dialog == nullptr) {
    return;
  }

  auto *progressDialog = static_cast<IProgressDialog *>(dialog);
  progressDialog->StopProgressDialog();
  progressDialog->Release();
  dialog = nullptr;
}
