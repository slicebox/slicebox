--- File descriptions ---
For more in depth usage documatation, read the in-file comments.

		makesbxdata - use this function to create an container for all the settings required to connect to a slicebox instance. The function will read some settings from the file sbxsettings.conf if it exists in the active matlab directory.

		sbxgetflatseries -- use this function to retrieve a flattened image of the patient database on the slicebox server. This can be used to manually browse through patients, studies or series, but is mainly used to populate the tables in the gui. Every time the function is used it will save the flattened image to the cache folder. If the server is offline or otherwise unreachable, the function will try to use the cached version instead.

		sbxgetimageinfo -- use this function to retrieve the information required to download the images in a series. The image information is saved to the cache directory and if the slicebox server is offline or otherwise unrechable the function will try to use the cached version.

		sbxreadimages -- this function will download the specified images and dicom headers from the slicebox server. The images will be saved in the cache directory, if not already present. If present, the images will be read from the cache instead.

		sbxreadseries -- will download the images and their dicom headers of the specified series. The images will be saved in the cache directory, if not already present. If present, the images will be read from the cache instead.

		SBXGui_osx -- provides a simple gui to select and download images from a slicebox server. Start the gui, providing a username and password, select a patient in the upper table and then select a series in the bottom table. Press Load to download the images. The GUI will terminate when all the images have been downloaded. The images will be saved in the cache directory, if not already present. If present, the images will be read from the cache instead. This GUI works best with OSX, but can be used with other OSes as well. You will find that when used with a different os, the font size will be slightly too large.

--- Example Usage ---
There are three ways of retrieving images. In all three cases, a sbxdata object must first be created, like so:

		sbxdata = makesbxdata(username, password);

provided a sbxsettings.conf file exists. Otherwise the options 'cachepath' and 'url' must also be provided. Don't forget to use specify the port in the url!

If the seriesid is known (you can find this by browsing the slicebox online interface, or by looking through the output of sbxgetflatseries), use sbxreadseries

		[I, dcminfo] = sbxreadseries(sbxdata, seriesid);

If you only want certain images from a series, you can first read the imageinfo, choose the images you want and then use sbxreadimages to load them.

		imageinfo = sbxgetimageinfo(sbxdata, seriesid);
		[I, dcminfo] = sbxreadimages(sbxdata, imageinfo(2:6));

To start the gui just write

		[I, dcminfo] = SBXGui(username, password);

Again, if the sbxsettings.conf doesn't exist, you must provide the 'cachcepath' and 'url' options.
