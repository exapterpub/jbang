///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.apache.commons:commons-compress:1.26.2

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GDown {

	private static final int CHUNK_SIZE = 32768;
	private static final String DOWNLOAD_URL = "https://docs.google.com/uc?export=download";

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.out
				.println("Usage: jbang gdrive_download.java <fileId> <destPath> [--overwrite] [--unzip] [--showsize]");
			return;
		}

		String fileId = args[0];
		Path destPath = Paths.get(args[1]);

		boolean overwrite = Arrays.asList(args).contains("--overwrite");
		boolean unzip = Arrays.asList(args).contains("--unzip");
		boolean showSize = Arrays.asList(args).contains("--showsize");

		downloadFileFromGoogleDrive(fileId, destPath, overwrite, unzip, showSize);
	}

	static String sizeofFmt(long num) {
		String[] units = { "", "Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "Zi" };
		double n = num;
		for (String unit : units) {
			if (Math.abs(n) < 1024.0) {
				return String.format("%.1f %sB", n, unit);
			}
			n /= 1024.0;
		}
		return String.format("%.1f YiB", n);
	}

	static void saveResponseContent(InputStream input, Path destination, boolean showSize) throws IOException {
		long currentSize = 0;
		try (BufferedInputStream in = new BufferedInputStream(input);
				OutputStream out = Files.newOutputStream(destination)) {

			byte[] buffer = new byte[CHUNK_SIZE];
			int bytesRead;

			while ((bytesRead = in.read(buffer)) != -1) {
				out.write(buffer, 0, bytesRead);
				currentSize += bytesRead;

				if (showSize) {
					System.out.print("\r" + sizeofFmt(currentSize) + " ");
					System.out.flush();
				}
			}
		}
	}

	static void downloadFileFromGoogleDrive(
			String fileId,
			Path destPath,
			boolean overwrite,
			boolean unzip,
			boolean showSize) throws Exception {

		Path dir = destPath.getParent();
		if (dir != null && !Files.exists(dir)) {
			Files.createDirectories(dir);
		}

		if (Files.exists(destPath) && !overwrite) {
			System.out.println("File exists, skipping download.");
			return;
		}

		System.out.print("Downloading " + fileId + " into " + destPath + "... ");
		System.out.flush();

		HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.build();

		String url = DOWNLOAD_URL + "&id=" + fileId + "&confirm=true";

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.POST(HttpRequest.BodyPublishers.noBody())
			.build();

		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

		if (showSize)
			System.out.println();

		saveResponseContent(response.body(), destPath, showSize);

		System.out.println("Done.");

		if (unzip) {
			try {
				System.out.print("Unzipping...");
				unzipFile(destPath, dir != null ? dir : Paths.get("."));
				System.out.println("Done.");
			} catch (Exception e) {
				System.err.println("Ignoring unzip: not a valid zip file.");
			}
		}
	}

	static void unzipFile(Path zipPath, Path targetDir) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				Path newPath = targetDir.resolve(entry.getName()).normalize();
				if (entry.isDirectory()) {
					Files.createDirectories(newPath);
				} else {
					Files.createDirectories(newPath.getParent());
					try (OutputStream out = Files.newOutputStream(newPath)) {
						zis.transferTo(out);
					}
				}
				zis.closeEntry();
			}
		}
	}

}
