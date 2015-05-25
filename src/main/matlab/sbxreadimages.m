function [I, dcminfo] = sbxreadimages(imagedata, sbxdata)
% SBXREADIMAGE Read an image from a slicebox server or a cached image
% from disk.
%
% I = SBXREADIMAGE(imagedata, sbxdata) Reads a dicom image specified by 
%        'imagedata', Return as 2d array. If the image is not present in
%        the cache it will first be downloaded from the slicebox service.

I = cell(1,length(imagedata));
dcminfo = cell(1,length(imagedata));
for i = 1:length(imagedata)
    filepath = [sbxdata.cachepath, '/', imagedata(i).sopInstanceUID.value,'.dcm'];
    if exist(filepath, 'file')~=2;
        imageurl = [sbxdata.url, '/api/images/', num2str(imagedata(i).id)];
        websave(filepath, imageurl, sbxdata.weboptions);
    end

    z = str2double(datasets(i).instanceNumber.value);
    I{z} = dicomread(filepath);
    dcminfo{z} = dicominfo(filepath);
end