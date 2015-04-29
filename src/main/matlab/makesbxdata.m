function sbxdata = makesbxdata(varargin)

if nargin == 2
    username = varargin{1};
    password = varargin{2};
else
    [username, password] = authprompt;
end

%create weboptions object
wo = weboptions('Username', username, 'Password', password');

%read settings from file
sbconf = fopen('sbconf.settings');
cells = textscan(sbconf,'%s %s','delimiter','=');
fclose(sbconf);

keynames = cells{1};
values = cells{2};

sbxdata = cell2struct(values, keynames);
sbxdata.weboptions = wo;

cachepath = sbxdata.cachepath;
if exist(cachepath, 'dir') ~= 7
    error('Cache directory does not exist!');
end