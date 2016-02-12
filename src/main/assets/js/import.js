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

.controller('ImportCtrl', function($scope, Upload, $q, sbxToast) {
    
    $scope.uiState.selectedSession = null;

    $scope.callbacks = {};

    var importSessions = []; // temporary

    $scope.loadImportSessions = function(startIndex, count) {
        return importSessions;
    };

    $scope.newImportSessionButtonClicked = function() {
        var id = importSessions.length === 0 ? 1 : importSessions[importSessions.length - 1].id + 1;
        var newSession = { id: id, name: "My Session " + id, created: new Date().getTime(), filesImported: 0, filesRejected: 0 };
        importSessions.push(newSession);
        $scope.uiState.selectedSeriesType = newSession;
        $scope.callbacks.importSessionsTable.reloadPage();
    };

    $scope.importSessionSelected = function(importSession) {
        $scope.uiState.selectedSession = importSession;
    };

    function importFirst(files) {
        if (files && files.length) {
            Upload.upload({
                url: '/api/images',
                file: files[0]
            }).success(function (data, status, headers, config) {
                //importedFiles.push({ name: config.file.name });
                files.shift();
                importFirst(files);
            }).error(function (message, status, headers, config) {
                if (status >= 300 && status !== 400) {
                    sbxToast.showErrorMessage('Error importing file: ' + message);
                }
                files.shift();
                importFirst(files);
            });
        }
    }

    $scope.import = function(files) {
        importFirst(files);
    };

});