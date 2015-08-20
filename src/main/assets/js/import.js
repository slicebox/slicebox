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

    function importFirst(files) {
        if (files && files.length) {
            Upload.upload({
                url: '/api/images',
                file: files[0]
            }).success(function (data, status, headers, config) {
                importedFiles.push({ name: config.file.name });
                $scope.callbacks.importedFilesTable.reset();
                files.shift();
                importFirst(files);
            }).error(function (message, status, headers, config) {
                var errorMessage = status === 400 ? "Not a valid DICOM file" : message;
                rejectedFiles.push({ name: config.file.name, status: status, message: errorMessage });
                $scope.callbacks.rejectedFilesTable.reset();
                files.shift();
                importFirst(files);
            });
        }
    }

    $scope.import = function(files) {
        importFirst(files);
    };

    $scope.getImportedFiles = function() {
        return $q.when(importedFiles);
    };

    $scope.getRejectedFiles = function() {
        return $q.when(rejectedFiles);
    };

});