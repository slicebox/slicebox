function I = sbxreadimage(imagedata, sbxdata)
% SBXREADIMAGE Read an image from a slicebox server or a cached image
% from disk.
%
% I = SBXREADIMAGE(imagedata, sbxdata) Reads a dicom image specified by 
%        'imagedata' return as 2d array. If the image is not present in
%        the cache it will first be downloaded from the slicebox service.

filepath = [sbxdata.cachepath, '/', imagedata.sopInstanceUID.value,'.dcm'];
if exist(filepath, 'file')~=2;
    imageurl = [sbxdata.url, '/api/images/', num2str(imagedata.id)];
    websave(filepath, imageurl, sbxdata.weboptions);
end 

I = dicomread(filepath);