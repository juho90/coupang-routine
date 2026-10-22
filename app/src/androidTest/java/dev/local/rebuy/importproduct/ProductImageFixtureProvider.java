package dev.local.rebuy.importproduct;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/** Framework/Java only: the standalone test APK process has no target APK Kotlin runtime. */
public final class ProductImageFixtureProvider extends ContentProvider {
    private static final String AUTHORITY = "dev.local.rebuy.test.product-image-fixture";
    private static final String IMAGE_PATH = "/synthetic-product.png";
    private static final String BROKEN_PATH = "/not-an-image.bin";
    private byte[] cachedPng;

    public static Uri imageUri() { return Uri.parse("content://" + AUTHORITY + IMAGE_PATH); }
    public static Uri brokenUri() { return Uri.parse("content://" + AUTHORITY + BROKEN_PATH); }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String path = checkedPath(uri);
        if (IMAGE_PATH.equals(path)) return "image/png";
        if (BROKEN_PATH.equals(path)) return "application/octet-stream";
        throw new IllegalArgumentException("Unknown synthetic fixture");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Synthetic fixtures are read-only");
        String path = checkedPath(uri);
        final byte[] bytes;
        if (IMAGE_PATH.equals(path)) bytes = pngBytes();
        else if (BROKEN_PATH.equals(path)) bytes = new byte[] { 1, 2, 3, 4 };
        else throw new FileNotFoundException("Unknown synthetic fixture");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException failure) { throw new FileNotFoundException("Cannot create synthetic fixture pipe"); }
        Thread writer = new Thread(new Runnable() {
            @Override public void run() {
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(bytes);
                } catch (IOException ignored) { /* Reader closed its own fixed-fixture pipe. */ }
            }
        }, "SyntheticProductImageWriter");
        writer.setDaemon(true);
        writer.start();
        return pipe[0];
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String path = checkedPath(uri);
        int size;
        if (IMAGE_PATH.equals(path)) size = pngBytes().length;
        else if (BROKEN_PATH.equals(path)) size = 4;
        else throw new IllegalArgumentException("Unknown synthetic fixture");
        String[] columns = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) values[index] = path.substring(1);
            else if (OpenableColumns.SIZE.equals(columns[index])) values[index] = size;
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only test fixture"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only test fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only test fixture"); }

    private String checkedPath(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() == null) {
            throw new IllegalArgumentException("Invalid synthetic fixture URI");
        }
        return uri.getPath();
    }

    private synchronized byte[] pngBytes() {
        if (cachedPng != null) return cachedPng;
        Bitmap bitmap = Bitmap.createBitmap(2000, 1050, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(80f);
            canvas.drawText("익스트림 트리플 아르기닌6200", 100f, 170f, paint);
            canvas.drawText("600ml, 2개", 100f, 370f, paint);
            paint.setTextSize(100f);
            canvas.drawText("75,000원", 100f, 610f, paint);
            canvas.drawText("67,500원", 100f, 850f, paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("Cannot encode synthetic fixture");
            cachedPng = output.toByteArray();
            return cachedPng;
        } finally { bitmap.recycle(); }
    }
}
