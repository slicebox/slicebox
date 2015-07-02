function [I, dcminfo] = sbxreadseries(seriesid, sbxdata, varargin)
% SBXREADSERIES Load an entire image series volume.
%
%   [I, dcminfo] = SBXREADSERIES(seriesid, sbxdata, varargin) Will retrieve the series
%       specified by the 'seriesid'. Any images not in cache will first be
%       downloaded to the cache directory.

datasets = sbxgetimageinfo(seriesid, sbxdata);
[I, dcminfo] = sbxreadimages(datasets,sbxdata);