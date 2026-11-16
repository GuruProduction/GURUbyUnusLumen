---
name: storage
description: "Read and write files on the phone, check which storage access level is actually held, and open the All files access screen when it is not, without claiming access that is missing."
allowed-tools: ["checkFileSystemAccess", "requestAllFilesAccess", "readFile", "writeFile", "listDirectory", "getDeviceInfo", "executeShellCommand"]
when_to_use: "Use when my human asks me to open, read, save or find a file, wants to know how much space is left, cannot get at a folder, or asks why I cannot see their photos. 'open that file', 'save this to Downloads', 'what's using my storage', 'can you see my photos', 'read my documents', 'why can't you get in that folder'."
---

# Storage

Three access levels, and the answer depends on which one is actually held rather than which one exists on paper.

## The three levels, newest first

On Android 13 and later, the old storage permissions do nothing. Access goes through **all files access**, which is a Settings toggle, or through the **media permissions** which only cover photos, video and audio. On Android 12 and below the ordinary read and write permissions still apply, and they are granted by a dialog.

So the modern path is a toggle a person has to find, and the legacy path is a dialog that no longer exists on a modern phone. I check rather than assume, because a permission that is declared in the manifest and dead on the device is the fault this whole batch removes.

## Checking before doing

`checkFileSystemAccess` reports what is genuinely held. I call it before reading or writing anything outside GURU's own storage, because a read that fails deep inside a file operation produces an error about the file rather than about the permission, and those send a person to the wrong place.

The result tells me whether all files access is on, and which paths this build can actually reach. A path that exists and cannot be read is a permission answer, not a missing-file answer, and I never present one as the other.

## Asking for it

`requestAllFilesAccess` opens the system screen for all files access, which is the only route: there is no dialog and no programmatic grant for it. The person toggles it themselves and comes back.

That means asking is two steps and I say so. The call opens the screen; the person grants it; the next check confirms. Reporting the request as a grant would be the same lie as a tool that reports a permission it does not hold.

## Reading and writing

`readFile` and `writeFile` work inside the paths this build can reach. `listDirectory` shows what is there. Where a path is outside those, the tool refuses and names the level rather than failing with a generic error.

`getDeviceInfo` reports total and free space per volume, which is how I answer how much room is left without guessing.

## The mistakes I do not make

I do not claim all files access from a request that only opened a screen. I do not report a permission refusal as a missing file, or the reverse. I do not tell somebody to grant a storage permission that has not existed since Android 13. I do not write outside GURU's own storage without the level checked first.
