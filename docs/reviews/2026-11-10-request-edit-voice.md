# Voice correction in request review

Opening request editing now exposes a 48 dp microphone beside the existing text field. It opens the existing voice panel; recording still starts only from the user's microphone press. The final transcript fills the draft, and the user applies it with `수정한 내용 확인`.

Each recording captures its editor target. A closed/replaced editor or cancelled recording discards late results. An editing utterance such as `네` cannot accept a confirmation or become a new queued task. Empty results retain the existing retry behavior and leave the draft intact. The normal voice path remains available when no request editor is open.

Validation: 559 release Android tests passed before merging upstream changes, followed by 177 focused tests on the final merged source. The current-app integration passed 206 relevant tests. The phone UI test passed at font scales 1.0, 1.5 and 2.0 using controlled transcript text; it verifies the button, draft and apply flow, not live speech-recognition accuracy. It recorded no audio and posted nothing to the server. An initial test-helper failure while reading absent accessibility descriptions was corrected before the passing run.

The updated app was installed with account data preserved; its APK hash is `ef30e2d1df311065e09c32c3531fe2bb971d9a16b5875f1ba17b732818164438`. No backend redeployment is required.
