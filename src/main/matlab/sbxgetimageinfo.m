function datasets = sbxgetimageinfo(seriesid, sbdata)
% Will fetch information about the dicom images of a series. The results
% are needed when using sbreadimage.
url = [sbdata.url, '/api/metadata/images?seriesid=',num2str(seriesid)];
datasets = webread(url, sbdata.weboptions);