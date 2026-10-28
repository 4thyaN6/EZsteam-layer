package app.ezsteam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only access to saved reports for the share sheet: content://app.ezsteam.reports/<file name>
 * serves that zip from Download/.EZlator/Reports, nothing else (no paths, only names starting with
 * the report prefix). The app grants read access per share (FLAG_GRANT_READ_URI_PERMISSION); the
 * provider itself is not exported. Own code instead of androidx FileProvider (no androidx here).
 * Why: reports went to the computer over USB and Windows Explorer kept freezing (user, 2026-10-28);
 * sharing sends the file straight to a chat or drive app.
 * 공유 창용 저장된 제보 읽기 전용 접근: content://app.ezsteam.reports/<파일 이름>은 Download/.EZlator/Reports의 그 zip만
 * 내줌(경로 없음, 제보 접두어로 시작하는 이름만). 앱이 공유할 때마다 읽기 권한을 줌(FLAG_GRANT_READ_URI_PERMISSION). 제공자
 * 자체는 외부에 공개하지 않음. androidx FileProvider 대신 자체 코드(여기에는 androidx 없음).
 * 이유: 제보를 USB로 컴퓨터에 옮기다 Windows 탐색기가 계속 멈췄음(사용자, 2026-10-28). 공유는 파일을 채팅·드라이브 앱으로
 * 바로 보냄
 */
public final class ReportProvider extends ContentProvider {

    static final String AUTHORITY = "app.ezsteam.reports";

    /** Content address of a saved report / 저장된 제보의 콘텐츠 주소 */
    static Uri uriFor(File report) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(report.getName()));
    }

    /** The report file for an address, or null when the name is not a plain report name
     *  주소에 해당하는 제보 파일. 일반 제보 이름이 아니면 null */
    private static File fileFor(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")
                || !name.startsWith(app.ezsteam.runtime.SharedStorage.REPORT_PREFIX) || !name.endsWith(".zip")) {
            return null;
        }
        try {
            File f = new File(app.ezsteam.runtime.SharedStorage.reports(), name);
            return f.isFile() ? f : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/zip";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.startsWith("r") || mode.contains("w")) throw new FileNotFoundException("read only");
        File f = fileFor(uri);
        if (f == null) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Name and size for the receiving app (OpenableColumns) / 받는 앱용 이름과 크기(OpenableColumns) */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        if (f != null) c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
