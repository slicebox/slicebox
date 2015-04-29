function I = sbxreadimage(imagedata, sbdata)
% Will read an dicom image from cache and return the image data as a 2d
% array. If the image is not present in the cache it will download the file
% from the slicebox defined in the sbdata and save it to the cache, then
% load the file.

filepath = [sbdata.cachepath, '/', imagedata.sopInstanceUID.value,'.dcm'];
if exist(filepath, 'file')~=2;
%     [~, file, ext] = fileparts(filepath);
%     fprintf('File %s.%s not in cache\nDownloading...\n',file,ext);
    imageurl = [sbdata.url, '/api/images/', num2str(imagedata.id)];
    websave(filepath, imageurl, sbdata.weboptions);
end 

I = dicomread(filepath);