// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

/** Name follow-up is permitted only after this exact upload was acknowledged as written. */
final class SfcUploadTitle {
    private final String hash,draft;
    private boolean used;
    SfcUploadTitle(String hash,String draft){this.hash=hash;this.draft=draft;}
    String draft(){return draft;}
    boolean afterUpload(String status,String writtenHash,String actualTitle){
        if(used||!"写入完成".equals(status)||!hash.equals(writtenHash))return false;
        used=true;return !draft.isBlank()&&!draft.equals(actualTitle);
    }
}
