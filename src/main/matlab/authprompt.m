function [username, password] = authprompt() 
  prompt = {'Username:','Password:'};
  dlg_title = 'Input';
  num_lines = 1;
  def = {'',''};
  answer = inputdlg(prompt,dlg_title,num_lines,def);
  username = answer{1};
  password = answer{2};
end