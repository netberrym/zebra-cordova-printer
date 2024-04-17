package com.github.michael79bxl.zbtprinter;

import java.io.IOException;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import android.util.Log;
import com.zebra.android.discovery.*;
import com.zebra.sdk.graphics.internal.ZebraImageAndroid;
import com.zebra.sdk.comm.*;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.*;

public class ZebraBluetoothPrinter extends CordovaPlugin {

	private static final String LOG_TAG = "ZebraBluetoothPrinter";
	// String mac = "AC:3F:A4:1D:7A:5C";

	public ZebraBluetoothPrinter() {
	}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {

		if (action.equals("print")) {
			try {
				String mac = args.getString(0);
				String msg = args.getString(1);
				String height = args.getString(2);
				sendData(callbackContext, mac, msg, height);
			} catch (Exception e) {
				Log.e(LOG_TAG, e.getMessage());
				e.printStackTrace();
			}
			return true;
		}
		if (action.equals("find")) {
			try {
				findPrinter(callbackContext);
			} catch (Exception e) {
				Log.e(LOG_TAG, e.getMessage());
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}

	public void findPrinter(final CallbackContext callbackContext) {
		try {
			BluetoothDiscoverer.findPrinters(this.cordova.getActivity().getApplicationContext(),
					new DiscoveryHandler() {

						public void foundPrinter(DiscoveredPrinter printer) {
							String macAddress = printer.address;
							// I found a printer! I can use the properties of a Discovered printer (address)
							// to make a Bluetooth Connection
							callbackContext.success(macAddress);
						}

						public void discoveryFinished() {
							// Discovery is done
						}

						public void discoveryError(String message) {
							// Error during discovery
							callbackContext.error(message);
						}
					});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/*
	 * This will send data to be printed by the bluetooth printer
	 */
	void sendData(final CallbackContext callbackContext, final String mac, final String msg, final String height)
			throws IOException {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					// Instantiate insecure connection for given Bluetooth MAC Address.
					Connection thePrinterConn = new BluetoothConnectionInsecure(mac);

					// Verify the printer is ready to print
					if (isPrinterReady(thePrinterConn)) {
						// Open the connection - physical connection is established here.
						thePrinterConn.open();
						thePrinterConn.write("! U1 setvar \"device.languages\" \"CPCL\"\r\n".getBytes());
						thePrinterConn.write("! U1 JOURNAL\r\n! U1 SETFF 50 2\r\n".getBytes());

						// MÉTODO NUEVO
						ZebraPrinter printer = ZebraPrinterFactory.getInstance(PrinterLanguage.CPCL, thePrinterConn);

						// Send the data to printer as a byte array.
						byte[] decodedString = Base64.decode(msg, Base64.DEFAULT);
						Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
						ZebraImageAndroid zebraImageToPrint = new ZebraImageAndroid(decodedByte);

						// MÉTODO NUEVO
						printer.storeImage("R:TEMP.PCX", zebraImageToPrint, -1, -1);

						String printString = "! 0 200 200 " + height + " 1\r\n" // tamaño de la impresion y cantidad de
																				// copias
								+ "PW 831\r\n" // ancho máximo de impresion
								+ "TONE 60\r\n" // intensidad de la impresión 0-200
								+ "SPEED 2\r\n" // velocidad de la impresion (menos = mas precíso) 1-2.5cm/s | 2-5cm/s |
												// 3-7.6cm/s
								+ "NO-PACE\r\n"
								+ "BAR-SENSE\r\n"
								+ "PCX 0 0 !<TEMP.PCX\r\n" // obtener la imagen que almacenamos antes en la impresora
								+ "FORM\r\n"
								+ "PRINT\r\n"; // imprimir
						// envío de los comandos a la impresora, la imagen se imprimirá ahora
						thePrinterConn.write(printString.getBytes());
						// borramos la imagen
						thePrinterConn.write("! U1 do \"file.delete\" \"R:TEMP.PCX\"\r\n".getBytes());

						// MÉTODO ANTIGUO
						// String setup = "^XA^MNN,50^LL"+height+"^XZ^XA^JUS^XZ";
						// thePrinterConn.write(setup.getBytes());
						// printer.printImage(new ZebraImageAndroid(decodedByte), 0, 0, 0, 0, false);

						// Make sure the data got to the printer before closing the connection
						Thread.sleep(10000);

						// Close the insecure connection to release resources.
						thePrinterConn.close();
						callbackContext.success("ok");
					} else {
						callbackContext.error("Impresora no disponible");
					}
				} catch (Exception e) {
					// Handle communications error here.
					callbackContext.error(e.getMessage());
				}
			}
		}).start();
	}

	private Boolean isPrinterReady(Connection connection)
			throws ConnectionException, ZebraPrinterLanguageUnknownException {
		Boolean isOK = false;
		ZebraPrinterLinkOs linkos_printer;

		connection.open();
		// Creates a ZebraPrinter object to use Zebra specific functionality like
		// getCurrentStatus()
		ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);

		// MÉTODO NUEVO
		try {
			linkos_printer = ZebraPrinterFactory.createLinkOsPrinter(printer);
		} catch (Exception e) {
			linkos_printer = null;
		}

		// MÉTODO NUEVO
		PrinterStatus printerStatus = (linkos_printer != null) ? linkos_printer.getCurrentStatus()
				: printer.getCurrentStatus();

		if (printerStatus.isReadyToPrint) {
			isOK = true;
		} else if (printerStatus.isPaused) {
			throw new ConnectionException("No se puede imprimir, la impresora esta en pausa");
		} else if (printerStatus.isHeadOpen) {
			throw new ConnectionException("No se puede imprimir, por favor cierre la tapa de la impresora");
		} else if (printerStatus.isPaperOut) {
			throw new ConnectionException("No se puede imprimir, no hay papel en la impresora");
		} else {
			throw new ConnectionException("No se puede imprimir");
		}

		return isOK;
	}
}
