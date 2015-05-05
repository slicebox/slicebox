function images = sbxreadseries(seriesid, sbxdata, varargin)
% SBXREADSERIES Load an entire image series volume.
%
%   I = SBXREADSERIES(seriesid, sbxdata, varargin) Will retrieve the series
%       specified by the 'seriesid'. Any images not in cache will first be
%       downloaded to the cache directory.

datasets = sbxgetimageinfo(seriesid, sbxdata);
images = cell(length(datasets),1);
for i = 1:length(datasets)
    z = str2double(datasets(i).instanceNumber.value);
    images{z} = sbimageread(datasets(i),sbxdata);
end

% TODO: possible to specify range of images to read, choose to get output
% as array?