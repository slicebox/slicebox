(function () {
   'use strict';
}());

angular.module('slicebox.import', ['ngRoute', 'ngFileUpload'])

.config(function($routeProvider) {
  $routeProvider.when('/import', {
    templateUrl: '/assets/partials/import.html',
    controller: 'ImportCtrl'
  });  
})

.controller('ImportCtrl', function($scope, Upload, $q) {
    
    var importedFiles = [];
    var rejectedFiles = [];

    $scope.callbacks = {};

    $scope.import = function(files) {
        if (files && files.length) {
            files.forEach(function(file) {
                Upload.upload({
                    url: '/api/images',
                    file: file
                }).success(function (data, status, headers, config) {
                    importedFiles.push({ name: config.file.name });
                    $scope.callbacks.importedFilesTable.reset();
                }).error(function (message, status, headers, config) {
                    rejectedFiles.push({ name: config.file.name, status: status, message: message });
                    $scope.callbacks.rejectedFilesTable.reset();
                });
            });
        }
    };

    $scope.getImportedFiles = function() {
        return $q.when(importedFiles);
    };

    $scope.getRejectedFiles = function() {
        return $q.when(rejectedFiles);
    };

});