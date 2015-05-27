function dbinfo = sbxflatseries( sbxdata )
%SBXFLATSERIES Fetches a complete listing of all patients and series.
%   Fetches a complete listing of all patients and series associated with
%   them. The datastructure is flattened and does not contain the
%   information required to select individual frames. This is structure is
%   mainly used to populate the patient list of the GUI.

try
    url = [sbxdata.url, '/api/metadata/flatseries'];
    dbinfo = webread(url, sbxdata.weboptions);
    dbinfofile = fullfile(sbxdata.cachepath, 'dbinfo');
    fprintf('saving %s', dbinfofile);
    save(dbinfofile,'dbinfo');
catch ME
    switch ME.identifier
        case 'MATLAB:webservices:CopyContentToDataStreamError'
            try
                dbinfo = readinfofile(seriesid, sbxdata);
            catch
                ME2 = MException('SBXImageinfo:noSuchSeriesInCache','Could not fetch database metadata. Connection could not be established and no cache file present',...
                    sbxdata.url);
                throw(ME2);
            end
        case 'MATLAB:webservices:HTTP401StatusCodeError'
            error('Authorization failed; Incorrect username or password.');
        otherwise
            rethrow(ME);
    end
end
end

function datasets = readinfofile(sbxdata) %#ok<STOUT>
seriesfile = fullfile(sbxdata.cachepath, 'dbinfo');
load(seriesfile,'dbinfo');
end

